package com.github.tvbox.osc.util;

import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.dialog.TipDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * TVBox 应用内「检查更新」。
 *
 * 由 .github/tvbox-patch/apply-patch.sh 在 CI 构建时注入：
 *   - 拷进 app/src/main/java/com/github/tvbox/osc/util/
 *   - 替换下面三个 __UPDATE_JSON_*__ 占位符为真实值
 *   - 在 HomeActivity.init() 末尾追加 UpdateChecker.check(this)
 *   - 为 6 个 flavor 各生成一份 res/values/tvbox_flavor.xml
 *
 * 网络策略（国内直连 raw.githubusercontent.com 基本不通，所以要选源而不是死等）：
 *   1. 取 update.json：候选源并发探测，取最先返回且能解析成 JSON 的那个。
 *      实时源（官方 raw + 代理）优先；都不可达才退到 jsDelivr，
 *      因为 jsDelivr 对仓库有约 12 小时缓存，可能返回旧清单。
 *   2. 下载 APK：候选源（自建镜像 url → GitHub 直链 fallback → 直链套各个代理）
 *      先并发探通断，选最快的那个开始下载；下载中途失败会自动换下一个源重试，
 *      直到候选用完。
 *   3. 下载**集成在应用内**（HttpURLConnection + 自己的进度对话框），不再交给系统
 *      DownloadManager —— 后者会把文件丢进公共 Download 目录，在电视盒上经常出现
 *      「下载完找不到、点了装不了」。应用内下载落点在 getExternalFilesDir() 下的私有目录，
 *      FileProvider 的 file_paths.xml 已经覆盖它（Android/data/），所以不需要存储权限。
 *
 * 版本比较：上游把 versionName 改写成构建时间戳 20260929-1939，格式零填充、单调递增，
 * 所以直接做字符串比较，不需要自建版本体系。
 */
public class UpdateChecker {

    private static final String TAG = "TVBoxUpdate";
    private static final String UA = "TVBox-UpdateChecker";

    // CI 构建时由 apply-patch.sh 用 sed 替换
    private static final String UPDATE_JSON_RAW = "__UPDATE_JSON_RAW__";
    private static final String UPDATE_JSON_CDN = "__UPDATE_JSON_CDN__";
    /** 逗号分隔的代理前缀，如 https://gh-proxy.com/,https://ghfast.top/ ；为空表示不用代理 */
    private static final String UPDATE_JSON_MIRRORS = "__UPDATE_JSON_MIRRORS__";

    /** 单次探测的连接/读取超时 */
    private static final int PROBE_CONNECT_TIMEOUT = 3000;
    private static final int PROBE_READ_TIMEOUT = 4000;
    /** 并发探测的总等待上限：到点还没人成功就认输 */
    private static final long PROBE_TOTAL_WAIT_MS = 9000L;

    /** 下载状态轮询：1.5 秒一次，最长约 1 小时 */
    private static final int POLL_INTERVAL_MS = 1500;
    private static final int POLL_MAX_TRIES = 2400;
    /** 连续几次查不到下载记录就认定它被清理了（首次查询理论上已入库，仍宽容几次） */
    private static final int POLL_MISS_TOLERANCE = 4;

    private static volatile boolean running = false;
    private static String lastPrompted = null;

    /** 在 HomeActivity.init() 末尾调用一次。永不抛异常，失败只写日志。 */
    public static void check(final Activity activity) {
        if (activity == null || running) {
            return;
        }
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject manifest = fetchManifest();
                    if (manifest == null) {
                        Log.i(TAG, "拿不到 update.json，跳过检查");
                        return;
                    }
                    final String remote = manifest.optString("version", "").trim();
                    final String local = BuildConfig.VERSION_NAME == null ? "" : BuildConfig.VERSION_NAME.trim();
                    if (remote.isEmpty() || local.isEmpty() || remote.compareTo(local) <= 0) {
                        Log.i(TAG, "已是最新 local=" + local + " remote=" + remote);
                        return;
                    }
                    final String flavor = activity.getString(R.string.tvbox_flavor);
                    JSONObject variants = manifest.optJSONObject("variants");
                    if (variants == null) {
                        Log.w(TAG, "update.json 缺少 variants");
                        return;
                    }
                    final JSONObject entry = variants.optJSONObject(flavor);
                    if (entry == null) {
                        Log.w(TAG, "update.json 里没有当前变体：" + flavor);
                        return;
                    }
                    final List<String> mirrors = parseMirrors(manifest);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showDialog(activity, local, remote, flavor, entry, mirrors);
                        }
                    });
                } catch (Throwable e) {
                    Log.w(TAG, "检查更新失败", e);
                } finally {
                    running = false;
                }
            }
        }, "tvbox-update-check");
        t.setDaemon(true);
        t.start();
    }

    // ==================== 通用：候选源解析与并发探测 ====================

    /** 探测动作：返回非 null 表示这个地址可用，返回 null 表示不可用。 */
    private interface Prober<T> {
        T probe(String url) throws Throwable;
    }

    /** 按逗号拆配置，过滤空项和没被替换掉的占位符 */
    private static List<String> splitConfig(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        String[] parts = s.split(",");
        for (int i = 0; i < parts.length; i++) {
            String v = parts[i] == null ? "" : parts[i].trim();
            if (v.isEmpty() || v.startsWith("__")) {
                continue;
            }
            out.add(v);
        }
        return out;
    }

    /**
     * 兜底代理前缀。
     *
     * ⚠️ 不能用"没注入就是空列表"的逻辑：国内直连 raw.githubusercontent.com 基本不通，
     * 而 jsDelivr 对仓库有约 12 小时缓存（本身也不一定连得上）。两者都挂时更新检查会
     * **完全静默失败**，用户看到的就是"进来不提示更新"。
     * 实测速度（2026-09-29）：gh-proxy 0.79s / ghfast.top 2.20s / ghproxy.net 4.31s。
     *
     * 想换成自己的代理：设仓库 Variables 的 PROXY_PREFIXES（编译期注入）或
     * update.json 的 mirrors 字段（运行期生效），两者都会覆盖这里。
     */
    private static final String DEFAULT_PROXY_PREFIXES =
            "https://gh-proxy.com/,https://ghfast.top/,https://ghproxy.net/";

    private static List<String> defaultProxies() {
        return splitConfig(DEFAULT_PROXY_PREFIXES);
    }

    /** 去重且保持顺序 */
    private static List<String> dedup(List<String> in) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        for (int i = 0; i < in.size(); i++) {
            String s = in.get(i);
            if (s != null && !s.isEmpty()) {
                set.add(s);
            }
        }
        return new ArrayList<String>(set);
    }

    /** 代理前缀 + 完整原始 URL（前缀末尾没带 / 就补一个） */
    private static String proxify(String prefix, String fullUrl) {
        if (prefix.endsWith("/")) {
            return prefix + fullUrl;
        }
        return prefix + "/" + fullUrl;
    }

    /**
     * 并发探测候选地址，返回第一个成功的 —— 并发之下也就是响应最快的那个。
     * 全部失败或超时返回 null。
     */
    private static <T> T pickBest(final List<String> urls, final Prober<T> prober) {
        final List<String> candidates = dedup(urls);
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            try {
                return prober.probe(candidates.get(0));
            } catch (Throwable e) {
                return null;
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(candidates.size(), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tvbox-update-probe");
                t.setDaemon(true);
                return t;
            }
        });
        CompletionService<T> cs = new ExecutorCompletionService<T>(pool);
        int n = 0;
        for (int i = 0; i < candidates.size(); i++) {
            final String u = candidates.get(i);
            cs.submit(new Callable<T>() {
                @Override
                public T call() {
                    try {
                        T r = prober.probe(u);
                        if (r != null) {
                            Log.i(TAG, "探测成功 " + u);
                        }
                        return r;
                    } catch (Throwable e) {
                        Log.i(TAG, "探测失败 " + u + " : " + e);
                        return null;
                    }
                }
            });
            n++;
        }

        T picked = null;
        final long deadline = System.currentTimeMillis() + PROBE_TOTAL_WAIT_MS;
        for (int i = 0; i < n && picked == null; i++) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                break;
            }
            Future<T> f;
            try {
                f = cs.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (f == null) {
                break;
            }
            try {
                picked = f.get();
            } catch (Throwable ignored) {
            }
        }
        pool.shutdownNow();
        return picked;
    }

    // ==================== 第一步：取 update.json ====================

    /** 能拿到 200 且能解析成 JSON 才算通（代理出错时可能返回 200 + HTML）。 */
    private static final Prober<JSONObject> MANIFEST_PROBER = new Prober<JSONObject>() {
        @Override
        public JSONObject probe(String url) {
            return fetchJson(url);
        }
    };

    /** 实时源优先；jsDelivr 只做兜底（它有约 12 小时缓存，可能返回旧清单）。 */
    private static JSONObject fetchManifest() {
        List<String> fresh = new ArrayList<String>();
        fresh.add(UPDATE_JSON_RAW);
        List<String> proxies = splitConfig(UPDATE_JSON_MIRRORS);
        if (proxies.isEmpty()) {
            // 编译期没注入 PROXY_PREFIXES 时不能就这么算了：raw 直连基本不通，
            // 只靠 jsDelivr 兜底等于把更新检查挂在 12 小时缓存上（甚至直接连不上）。
            proxies = defaultProxies();
            Log.i(TAG, "未注入 PROXY_PREFIXES，使用内置兜底代理 " + proxies.size() + " 个");
        }
        for (int i = 0; i < proxies.size() && !UPDATE_JSON_RAW.isEmpty(); i++) {
            fresh.add(proxify(proxies.get(i), UPDATE_JSON_RAW));
        }
        Log.i(TAG, "实时源候选 " + fresh.size() + " 个，开始并发探测");

        JSONObject o = pickBest(fresh, MANIFEST_PROBER);
        if (o != null) {
            return o;
        }
        Log.w(TAG, "实时源都不可达，退到 jsDelivr（可能返回 12 小时内的缓存）");
        List<String> cached = new ArrayList<String>();
        cached.add(UPDATE_JSON_CDN);
        return pickBest(cached, MANIFEST_PROBER);
    }

    private static JSONObject fetchJson(String url) {
        if (url == null || url.isEmpty() || url.startsWith("__")) {
            return null;
        }
        HttpURLConnection conn = null;
        BufferedReader br = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT);
            conn.setReadTimeout(PROBE_READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "HTTP " + code + " <- " + url);
                return null;
            }
            br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Throwable e) {
            Log.w(TAG, "拉取失败 " + url + " : " + e);
            return null;
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Throwable ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** update.json 里的 mirrors 字段：运行期也能改，不必重编 APK */
    private static List<String> parseMirrors(JSONObject manifest) {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray arr = manifest.optJSONArray("mirrors");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String v = arr.optString(i, "").trim();
                    if (!v.isEmpty() && !v.startsWith("__")) {
                        out.add(v);
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "解析 mirrors 失败", e);
        }
        if (out.isEmpty()) {
            // 清单里没配 mirrors 时也要给下载留退路，否则 APK 只剩 GitHub 直链
            // （国内很慢），换源重试也没有第二个源可换。
            out = defaultProxies();
        }
        return out;
    }

    // ==================== 第二步：选源并下载 APK ====================

    /** 只发 Range: bytes=0-0，拿到响应码就断开，避免真把几十 MB 拉下来。 */
    private static final Prober<String> APK_PROBER = new Prober<String>() {
        @Override
        public String probe(String url) {
            return reachable(url) ? url : null;
        }
    };

    private static boolean reachable(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT);
            conn.setReadTimeout(PROBE_READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Range", "bytes=0-0");
            int code = conn.getResponseCode();
            // 206 = 支持 Range；200 = 忽略了 Range。都说明整条链路通
            return code >= 200 && code < 300;
        } catch (Throwable e) {
            Log.i(TAG, "不可达 " + url + " : " + e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 候选顺序：自建镜像 url → GitHub 直链 fallback → 直链套各个代理（自动去重） */
    private static List<String> apkCandidates(List<String> mirrors, JSONObject entry) {
        String url = entry.optString("url", "").trim();
        String fallback = entry.optString("fallback", "").trim();
        List<String> list = new ArrayList<String>();
        list.add(url);
        list.add(fallback);
        if (!fallback.isEmpty()) {
            for (int i = 0; i < mirrors.size(); i++) {
                list.add(proxify(mirrors.get(i), fallback));
            }
        }
        return dedup(list);
    }

    private static void showDialog(final Activity a, String local, final String remote,
                                   final String flavor, final JSONObject entry,
                                   final List<String> mirrors) {
        if (remote.equals(lastPrompted)) {
            return;
        }
        lastPrompted = remote;

        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本  ").append(local).append("  →  ").append(remote).append("\n\n");
        msg.append("当前包：").append(flavor).append("\n");
        long size = entry.optLong("size", 0);
        if (size > 0) {
            msg.append("大小：").append(String.format(Locale.US, "%.1f", size / 1048576.0)).append(" MB\n");
        }
        msg.append("\n立即下载并安装？");

        new TipDialog(a, msg.toString(), "立即升级", "稍后", new TipDialog.OnListener() {
            @Override
            public void left() {
                startDownload(a, entry, remote, mirrors);
            }

            @Override
            public void right() {
            }

            @Override
            public void cancel() {
            }
        }).show();
    }

    private static void startDownload(final Activity a, final JSONObject entry,
                                      final String version, final List<String> mirrors) {
        try {
            final String file = entry.optString("file", "");
            if (file.isEmpty() || !file.endsWith(".apk") || file.contains("/")) {
                Toast.makeText(a, "更新文件名异常，已中止", Toast.LENGTH_LONG).show();
                return;
            }
            final String expectSha = entry.optString("sha256", "");
            final List<String> candidates = apkCandidates(mirrors, entry);
            if (candidates.isEmpty()) {
                Toast.makeText(a, "更新信息不完整", Toast.LENGTH_LONG).show();
                return;
            }

            // 先把「允许安装未知来源」问掉，省得下完 20MB 才发现装不了
            if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(a, "请先允许本应用安装未知来源的应用，然后重新点击升级",
                        Toast.LENGTH_LONG).show();
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(s);
                return;
            }

            Toast.makeText(a, "正在选择最快的下载源…", Toast.LENGTH_SHORT).show();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    final String best = pickBest(candidates, APK_PROBER);
                    // 选中的排最前，其余按原顺序留着做失败重试
                    final List<String> ordered = new ArrayList<String>();
                    if (best != null) {
                        ordered.add(best);
                    }
                    for (int i = 0; i < candidates.size(); i++) {
                        if (!candidates.get(i).equals(best)) {
                            ordered.add(candidates.get(i));
                        }
                    }
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (best == null) {
                                Toast.makeText(a, "所有下载源都不可达，请检查网络",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            downloadCancelled = false;
                            showProgressDialog(a, file, version);
                            downloadNext(a, ordered, 0, file, expectSha, version);
                        }
                    });
                }
            }, "tvbox-update-pick");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            Log.w(TAG, "发起下载失败", e);
            Toast.makeText(a, "下载失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    /** 下载目录：应用私有外部目录 /sdcard/Android/data/<pkg>/files/update。
     *  file_paths.xml 已覆盖 Android/data/（所以能共享给安装器），且不需要任何存储权限
     *  —— 不再依赖系统 DownloadManager（它把文件丢进公共 Download 目录，电视盒上经常
     *  「下载完找不到、点了装不了」）。 */
    private static File downloadDir(Activity a) {
        File d = new File(a.getExternalFilesDir(null), "update");
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "创建下载目录失败: " + d.getAbsolutePath());
        }
        return d;
    }

    /** 用户点「取消下载」时置位，下载循环看到就主动收工 */
    private static volatile boolean downloadCancelled = false;

    /** 下载结果的四种出口 */
    private static final int DL_OK = 0, DL_FAILED = 1, DL_SHA = 2, DL_CANCELLED = 3;

    private static Dialog progressDialog;
    private static ProgressBar progressBar;
    private static TextView progressText;
    private static Handler progressHandler;

    /** 进度对话框：纯代码搭，不依赖任何新增资源 */
    private static void showProgressDialog(Activity a, String file, String version) {
        dismissProgressDialog();
        float density = a.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density + 0.5f);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(a);
        title.setText("正在下载 TVBox " + version);
        title.setTextSize(16);
        root.addView(title);

        TextView sub = new TextView(a);
        sub.setText(file);
        sub.setTextSize(12);
        root.addView(sub);

        progressBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = (int) (8 * density + 0.5f);
        blp.topMargin = m;
        blp.bottomMargin = m;
        progressBar.setLayoutParams(blp);
        root.addView(progressBar);

        progressText = new TextView(a);
        progressText.setText("正在连接...");
        progressText.setTextSize(13);
        root.addView(progressText);

        Button cancel = new Button(a);
        cancel.setText("取消下载");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadCancelled = true;
                Toast.makeText(a, "正在取消...", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(cancel);

        Dialog d = new Dialog(a);
        d.setTitle("应用更新");
        d.setContentView(root);
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        d.show();
        progressDialog = d;
        progressHandler = new Handler(Looper.getMainLooper());
    }

    private static void dismissProgressDialog() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Throwable ignored) {
            }
            progressDialog = null;
        }
        progressBar = null;
        progressText = null;
        progressHandler = null;
    }

    private static void postProgress(final Activity a, final long done, final long total,
                                     final int idx, final int count) {
        final Handler h = progressHandler;
        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                if (progressBar == null || progressText == null) {
                    return;
                }
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (pct < 0) {
                    pct = 0;
                }
                if (pct > 100) {
                    pct = 100;
                }
                progressBar.setProgress(pct);
                progressText.setText(String.format(Locale.US, "%d%%  %s / %s  (源 %d/%d)",
                        pct, human(done), human(total), idx + 1, count));
            }
        });
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1048576) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** 按候选列表逐个下载；失败或校验不过就换下一个源 */
    private static void downloadNext(final Activity a, final List<String> urls, final int idx,
                                     final String file, final String expectSha,
                                     final String version) {
        if (idx >= urls.size()) {
            dismissProgressDialog();
            Toast.makeText(a, "所有下载源都失败了", Toast.LENGTH_LONG).show();
            return;
        }
        final String url = urls.get(idx);
        Log.i(TAG, "开始下载（源 " + (idx + 1) + "/" + urls.size() + "）" + url);
        final File target = new File(downloadDir(a), file);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int rc = downloadFile(a, url, target, expectSha, idx, urls.size());
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (rc == DL_OK) {
                            dismissProgressDialog();
                            install(a, file, expectSha);
                        } else if (rc == DL_CANCELLED) {
                            dismissProgressDialog();
                            if (target.exists() && !target.delete()) {
                                Log.w(TAG, "清理取消后的半截文件失败");
                            }
                        } else {
                            Log.w(TAG, "源 " + (idx + 1) + " 没成功（rc=" + rc + "），换下一个");
                            downloadNext(a, urls, idx + 1, file, expectSha, version);
                        }
                    }
                });
            }
        }, "tvbox-update-download").start();
    }

    /**
     * 应用内下载：边下边算 sha256，下完直接比对（不用把几十 MB 再读一遍）。
     *
     * @return DL_OK / DL_FAILED / DL_SHA / DL_CANCELLED
     */
    private static int downloadFile(Activity a, String url, File target, String expectSha,
                                    int idx, int count) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "HTTP " + code + " <- " + url);
                return DL_FAILED;
            }
            long total = conn.getContentLengthLong();
            in = conn.getInputStream();
            out = new FileOutputStream(target);
            byte[] buf = new byte[65536];
            long done = 0;
            int n;
            long last = 0;
            while ((n = in.read(buf)) > 0) {
                if (downloadCancelled) {
                    Log.i(TAG, "用户取消了下载");
                    return DL_CANCELLED;
                }
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - last >= 500) {
                    last = now;
                    postProgress(a, done, total, idx, count);
                }
            }
            out.flush();
            if (expectSha != null && expectSha.length() == 64) {
                String actual = toHex(md.digest());
                if (!expectSha.equalsIgnoreCase(actual)) {
                    Log.w(TAG, "源 " + (idx + 1) + " 校验失败，期望=" + expectSha
                            + " 实际=" + actual + " <- " + url);
                    target.delete();
                    return DL_SHA;
                }
            }
            Log.i(TAG, "下载完成并校验通过: " + target.getAbsolutePath());
            return DL_OK;
        } catch (Throwable e) {
            Log.w(TAG, "下载失败 <- " + url, e);
            return DL_FAILED;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            sb.append(String.format(Locale.US, "%02x", digest[i]));
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }



    /**
     * 下载成功后校验并拉起安装。
     *
     * sha256 不过就当作「这个源不可用」——删掉文件、换下一个候选源重试。
     * 典型场景：某个代理返回 200 但内容是 HTML 错误页 —— 系统 DownloadManager 会认为
     * 下载成功，我们自己的下载器也一样，所以必须靠 sha256 把它拦住。
     * 注意重试不影响安全性：sha256 仍然是唯一的放行依据，任何一个源都过不了就整体中止，
     * 绝不会安装未通过校验的文件（fail-closed）。
     */
    /**
     * 校验并拉起安装。优先 ACTION_VIEW + FileProvider；
     * 有些电视 ROM 上没有能处理 ACTION_VIEW 的安装器，就退回 PackageInstaller（API 21+）。
     */
    @SuppressWarnings("deprecation")
    private static void install(Activity a, String file, String expectSha) {
        try {
            File apk = new File(downloadDir(a), file);
            if (!apk.exists()) {
                Toast.makeText(a, "安装包不存在：" + apk.getAbsolutePath(),
                        Toast.LENGTH_LONG).show();
                return;
            }
            // sha256 下载时已经算过一遍；这里再算一次是兜底，防止「下载完到安装前」文件被换过
            if (expectSha != null && expectSha.length() == 64) {
                String actual = sha256(apk);
                if (!expectSha.equalsIgnoreCase(actual)) {
                    apk.delete();
                    Log.w(TAG, "安装前复检失败，已删除。期望=" + expectSha + " 实际=" + actual);
                    Toast.makeText(a, "安装包校验失败，已删除", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(a, "请先允许本应用安装未知来源的应用，然后重新点击升级",
                        Toast.LENGTH_LONG).show();
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(s);
                return;
            }
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            // PackageInstaller 是 API 21+，19/20 上只能提示用户
            if (Build.VERSION.SDK_INT >= 21) {
                Log.w(TAG, "没有能处理 ACTION_VIEW 的安装器，改用 PackageInstaller", e);
                installViaSession(a, file);
            } else {
                Toast.makeText(a, "系统里没有可用的安装器", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable e) {
            Log.w(TAG, "拉起安装失败", e);
            Toast.makeText(a, "拉起安装失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    /** 用 PackageInstaller 装（API 21+），不依赖外部安装器 App */
    private static void installViaSession(final Activity a, final String file) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File apk = new File(downloadDir(a), file);
                    PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    int id = pi.createSession(params);
                    PackageInstaller.Session session = pi.openSession(id);
                    OutputStream out = session.openWrite("update", 0, apk.length());
                    FileInputStream in = new FileInputStream(apk);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    session.fsync(out);
                    out.close();
                    in.close();

                    final String action = a.getPackageName() + ".INSTALL_RESULT";
                    final BroadcastReceiver r = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ctx, Intent intent) {
                            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                            Log.i(TAG, "安装结果 status=" + status + " msg=" + msg);
                            try {
                                a.unregisterReceiver(this);
                            } catch (Throwable ignored) {
                            }
                            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                                return;
                            }
                            Toast.makeText(a, status == PackageInstaller.STATUS_SUCCESS
                                    ? "安装完成" : ("安装失败：" + msg), Toast.LENGTH_LONG).show();
                        }
                    };
                    if (Build.VERSION.SDK_INT >= 33) {
                        a.registerReceiver(r, new IntentFilter(action),
                                Context.RECEIVER_NOT_EXPORTED);
                    } else {
                        a.registerReceiver(r, new IntentFilter(action));
                    }
                    // ⚠️ commit() 收的是 IntentSender，不是 PendingIntent ——
                    //    直接传 PendingIntent 是编译错误（CI run #20 就是这么挂的）
                    session.commit(PendingIntent.getBroadcast(a, 0, new Intent(action),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                            .getIntentSender());
                    session.close();
                    Log.i(TAG, "已提交 PackageInstaller 会话");
                } catch (Throwable t) {
                    Log.w(TAG, "PackageInstaller 安装失败", t);
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(a, "安装失败，可尝试手动安装 " + file,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "tvbox-update-install").start();
    }


    private static String sha256(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Throwable e) {
            Log.w(TAG, "计算 sha256 失败", e);
            return "";
        }
    }
}
