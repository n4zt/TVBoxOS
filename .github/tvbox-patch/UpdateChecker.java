package com.github.tvbox.osc.util;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.dialog.TipDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
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
 *      先并发探通断，选最快的那个交给 DownloadManager；
 *      下载中途失败会自动换下一个源重试，直到候选用完。
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

            if (Build.VERSION.SDK_INT >= 23) {
                // 目标是公共 Download 目录，部分 ROM 需要存储权限
                ActivityCompat.requestPermissions(a,
                        new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 0x7751);
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
                            enqueueDownload(a, ordered, 0, file, expectSha, version);
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

    /** 按候选列表逐个尝试，失败自动换下一个源 */
    private static void enqueueDownload(final Activity a, final List<String> urls, final int idx,
                                        final String file, final String expectSha,
                                        final String version) {
        if (idx >= urls.size()) {
            Toast.makeText(a, "所有下载源都失败了", Toast.LENGTH_LONG).show();
            return;
        }
        final String url = urls.get(idx);
        try {
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(a, "系统下载服务不可用", Toast.LENGTH_LONG).show();
                return;
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("TVBox " + version);
            req.setDescription((idx == 0 ? "" : "备用源 " + (idx + 1) + " · ") + file);
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file);

            long id = dm.enqueue(req);
            Log.i(TAG, "下载源 " + (idx + 1) + "/" + urls.size() + " -> " + url);
            Toast.makeText(a, idx == 0 ? ("开始下载 " + version)
                            : ("换备用源重试（第 " + (idx + 1) + " 个）"),
                    Toast.LENGTH_SHORT).show();
            pollDownload(a, dm, id, file, expectSha, urls, idx, version);
        } catch (Throwable e) {
            Log.w(TAG, "发起下载失败，换下一个源", e);
            enqueueDownload(a, urls, idx + 1, file, expectSha, version);
        }
    }

    /** DownloadManager 没有可靠的跨进程回调，直接轮询；最长等约 1 小时。 */
    private static void pollDownload(final Activity a, final DownloadManager dm, final long id,
                                     final String file, final String expectSha,
                                     final List<String> urls, final int idx, final String version) {
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                tries++;
                Cursor c = null;
                int failedReason = -1;
                try {
                    c = dm.query(new DownloadManager.Query().setFilterById(id));
                    if (c != null && c.moveToFirst()) {
                        int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            install(a, file, expectSha);
                            return;
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            failedReason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "查询下载状态失败", e);
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }

                if (failedReason >= 0) {
                    Log.w(TAG, "下载源 " + (idx + 1) + " 失败，原因码 " + failedReason);
                    // 清掉失败任务和可能留下的半截文件，否则换源会撞 ERROR_FILE_ALREADY_EXISTS
                    try {
                        dm.remove(id);
                    } catch (Throwable e) {
                        Log.w(TAG, "移除失败任务出错", e);
                    }
                    deletePartial(file);
                    enqueueDownload(a, urls, idx + 1, file, expectSha, version);
                    return;
                }
                if (tries < 2400) {
                    h.postDelayed(this, 1500);
                }
            }
        }, 1500);
    }

    @SuppressWarnings("deprecation")
    private static void deletePartial(String file) {
        try {
            File f = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), file);
            if (f.exists()) {
                Log.i(TAG, "清理半截文件 " + file + " -> " + f.delete());
            }
        } catch (Throwable e) {
            Log.w(TAG, "清理半截文件出错", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void install(Activity a, String file, String expectSha) {
        try {
            File apk = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), file);
            if (!apk.exists()) {
                Toast.makeText(a, "安装包不存在：" + apk.getAbsolutePath(), Toast.LENGTH_LONG).show();
                return;
            }
            if (expectSha != null && expectSha.length() == 64) {
                String actual = sha256(apk);
                if (!expectSha.equalsIgnoreCase(actual)) {
                    apk.delete();
                    Log.w(TAG, "校验失败 期望=" + expectSha + " 实际=" + actual);
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
            Toast.makeText(a, "系统里没有可用的安装器", Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Log.w(TAG, "拉起安装失败", e);
            Toast.makeText(a, "拉起安装失败：" + e, Toast.LENGTH_LONG).show();
        }
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
