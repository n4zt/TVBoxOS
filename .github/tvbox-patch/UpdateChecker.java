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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * TVBox 应用内「检查更新」。
 *
 * 由 .github/tvbox-patch/apply-patch.sh 在 CI 构建时注入：
 *   - 拷进 app/src/main/java/com/github/tvbox/osc/util/
 *   - 替换下面两个 __UPDATE_JSON_*__ 占位符为真实地址
 *   - 在 HomeActivity.init() 末尾追加 UpdateChecker.check(this)
 *   - 为 6 个 flavor 各生成一份 res/values/tvbox_flavor.xml
 *
 * 版本比较：上游把 versionName 改写成构建时间戳 20260914-1520，格式零填充、单调递增，
 * 所以直接做字符串比较，不需要自建版本体系。
 */
public class UpdateChecker {

    private static final String TAG = "TVBoxUpdate";

    // CI 构建时由 apply-patch.sh 用 sed 替换
    private static final String UPDATE_JSON_RAW = "__UPDATE_JSON_RAW__";
    private static final String UPDATE_JSON_CDN = "__UPDATE_JSON_CDN__";

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
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showDialog(activity, local, remote, flavor, entry);
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

    /** 先试 GitHub raw，失败再试 jsDelivr。 */
    private static JSONObject fetchManifest() {
        String[] urls = new String[]{UPDATE_JSON_RAW, UPDATE_JSON_CDN};
        for (String u : urls) {
            if (u == null || u.isEmpty() || u.startsWith("__")) {
                continue;
            }
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "TVBox-UpdateChecker");
                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "HTTP " + code + " <- " + u);
                    continue;
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();
                return new JSONObject(sb.toString());
            } catch (Throwable e) {
                Log.w(TAG, "拉取失败 " + u, e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return null;
    }

    private static void showDialog(final Activity a, String local, final String remote,
                                   final String flavor, final JSONObject entry) {
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
                startDownload(a, entry, remote);
            }

            @Override
            public void right() {
            }

            @Override
            public void cancel() {
            }
        }).show();
    }

    private static void startDownload(final Activity a, final JSONObject entry, final String version) {
        try {
            final String file = entry.optString("file", "");
            String url = entry.optString("url", "");
            if (url.isEmpty()) {
                url = entry.optString("fallback", "");
            }
            if (file.isEmpty() || url.isEmpty()) {
                Toast.makeText(a, "更新信息不完整", Toast.LENGTH_LONG).show();
                return;
            }
            if (!file.endsWith(".apk") || file.contains("/")) {
                Toast.makeText(a, "更新文件名异常，已中止", Toast.LENGTH_LONG).show();
                return;
            }

            if (Build.VERSION.SDK_INT >= 23) {
                // 目标是公共 Download 目录，部分 ROM 需要存储权限
                ActivityCompat.requestPermissions(a,
                        new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 0x7751);
            }

            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(a, "系统下载服务不可用", Toast.LENGTH_LONG).show();
                return;
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("TVBox " + version);
            req.setDescription(file);
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file);

            long id = dm.enqueue(req);
            Toast.makeText(a, "开始下载 " + version, Toast.LENGTH_SHORT).show();
            pollDownload(a, dm, id, file, entry.optString("sha256", ""));
        } catch (Throwable e) {
            Log.w(TAG, "发起下载失败", e);
            Toast.makeText(a, "下载失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    /** DownloadManager 没有可靠的跨进程回调，直接轮询；最长等约 1 小时。 */
    private static void pollDownload(final Activity a, final DownloadManager dm, final long id,
                                     final String file, final String expectSha) {
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                tries++;
                Cursor c = null;
                try {
                    c = dm.query(new DownloadManager.Query().setFilterById(id));
                    if (c != null && c.moveToFirst()) {
                        int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            install(a, file, expectSha);
                            return;
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            int reason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
                            Toast.makeText(a, "下载失败（原因码 " + reason + "）", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "查询下载状态失败", e);
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
                if (tries < 2400) {
                    h.postDelayed(this, 1500);
                }
            }
        }, 1500);
    }

    @SuppressWarnings("deprecation")
    private static void install(Activity a, String file, String expectSha) {
        try {
            File apk = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), file);
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
                Toast.makeText(a, "请先允许本应用安装未知来源的应用，然后重新点击升级", Toast.LENGTH_LONG).show();
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
