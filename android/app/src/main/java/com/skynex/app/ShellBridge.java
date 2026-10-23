package com.skynex.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 注入给网页的原生桥（window.AndroidShell）。
 *
 * 存在的原因：Android WebView 不支持 <a download>，而应用的导出（存档 / 图片 / 主题）
 * 全部是「createObjectURL + a.download + a.click()」，在 WebView 里点了完全没反应。
 * 这里把数据分块接过来，直接写进系统「下载」目录：
 *   - API 29+ 走 MediaStore（Download/skynex/xxx），不需要任何权限；
 *   - API < 29 先试公共下载目录，没权限就落到应用专属目录（路径会 toast 出来）。
 *
 * 分块是因为 JavascriptInterface 传超大字符串（几十 MB 的 base64）会卡死甚至 OOM。
 */
public class ShellBridge {

    private static final String TAG = "SkynexShell";
    private static final String FOLDER = "skynex";
    private static final int BUF = 64 * 1024;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private OutputStream out;
    private Uri pendingUri;
    private File pendingFile;
    private String pendingName = "";
    private long written = 0L;

    ShellBridge(Context context) {
        this.ctx = context.getApplicationContext();
    }

    // ---------------- 状态 / 调试 ----------------

    @JavascriptInterface
    public void ready() {
        Log.i(TAG, "web shell ready");
    }

    @JavascriptInterface
    public void log(String msg) {
        Log.i(TAG, msg == null ? "" : msg);
    }

    @JavascriptInterface
    public void toast(final String msg) {
        if (msg == null) return;
        main.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    // ---------------- 文件保存（分块） ----------------

    /** 开始一个文件的写入；返回 false 表示没法创建目标文件 */
    @JavascriptInterface
    public boolean saveStart(String name, String mime) {
        discardPending();
        pendingName = sanitize(name);
        String type = (mime == null || mime.trim().isEmpty()) ? "application/octet-stream" : mime.trim();
        written = 0L;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver cr = ctx.getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, pendingName);
                cv.put(MediaStore.Downloads.MIME_TYPE, type);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    Log.e(TAG, "MediaStore insert returned null");
                    return false;
                }
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) {
                    cr.delete(uri, null, null);
                    return false;
                }
                pendingUri = uri;
                out = new BufferedOutputStream(os, BUF);
                return true;
            }

            // API 23-28：先公共下载目录，写不了退回应用专属目录
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdirs failed: " + dir);
                File f = new File(dir, pendingName);
                pendingFile = f;
                out = new BufferedOutputStream(new FileOutputStream(f), BUF);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "public downloads not writable, falling back to app dir", e);
                pendingFile = null;
                dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER);
                if (!dir.exists() && !dir.mkdirs()) return false;
                File f = new File(dir, pendingName);
                pendingFile = f;
                out = new BufferedOutputStream(new FileOutputStream(f), BUF);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "saveStart failed", e);
            discardPending();
            return false;
        }
    }

    /** 接收一段 base64 数据（512KB 二进制 ≈ 683KB 字符串） */
    @JavascriptInterface
    public void saveChunk(String b64) {
        if (out == null || b64 == null) return;
        try {
            byte[] buf = Base64.decode(b64, Base64.DEFAULT);
            out.write(buf);
            written += buf.length;
        } catch (Exception e) {
            Log.e(TAG, "saveChunk failed", e);
            discardPending();
            toast("保存失败：写入出错");
        }
    }

    /** 收尾并让文件在系统里可见 */
    @JavascriptInterface
    public boolean saveFinish() {
        if (out == null) return false;
        try {
            out.flush();
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "close failed", e);
            discardPending();
            return false;
        }
        out = null;

        String shown;
        try {
            if (pendingUri != null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                ctx.getContentResolver().update(pendingUri, cv, null, null);
                shown = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + pendingName;
            } else if (pendingFile != null) {
                shown = pendingFile.getAbsolutePath();
            } else {
                shown = pendingName;
            }
        } catch (Exception e) {
            Log.e(TAG, "saveFinish update failed", e);
            shown = pendingName;
        }

        pendingUri = null;
        pendingFile = null;
        toast("已保存：" + shown + "（" + humanSize(written) + "）");
        return true;
    }

    @JavascriptInterface
    public void saveAbort() {
        discardPending();
    }

    // ---------------- 内部 ----------------

    private void discardPending() {
        if (out != null) {
            try { out.close(); } catch (Exception ignored) {}
            out = null;
        }
        if (pendingUri != null) {
            try { ctx.getContentResolver().delete(pendingUri, null, null); } catch (Exception ignored) {}
            pendingUri = null;
        }
        if (pendingFile != null) {
            try {
                if (pendingFile.exists() && !pendingFile.delete()) Log.w(TAG, "delete failed: " + pendingFile);
            } catch (Exception ignored) {}
            pendingFile = null;
        }
        written = 0L;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        return String.format(java.util.Locale.US, "%.2f MB", kb / 1024.0);
    }

    /** 去掉路径和非法字符，避免文件名带 ../ 之类的东西 */
    private static String sanitize(String name) {
        String n = name == null ? "" : name.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty() || ".".equals(n) || "..".equals(n)) {
            n = "skynex-" + System.currentTimeMillis();
        }
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            n = (dot > 0 && dot < n.length() - 1)
                    ? n.substring(0, 100) + n.substring(dot)
                    : n.substring(0, 120);
        }
        return n;
    }
}

