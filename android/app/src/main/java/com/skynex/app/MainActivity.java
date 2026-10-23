package com.skynex.app;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;

/**
 * skynex 的 Android 壳。
 *
 * 三件事：
 *  1. 全屏沉浸式：隐藏状态栏 + 导航栏（上滑临时呼出），内容铺满屏幕；
 *  2. 键盘不挡输入框：API 30+ 监听 IME inset 给 WebView 撑出底部空间，
 *     API < 30 退回「窗口跟随键盘缩放」（老系统拿不到 ime() inset）；
 *  3. 返回不退出应用：系统返回键 / 边缘右滑返回 → 先走 WebView 的 history（应用自己
 *     用 popstate 实现了逐层返回），真的没得退了才「再按一次退出」。
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "SkynexMain";
    private static final long EXIT_WINDOW_MS = 2000L;

    private long lastBackAt = 0L;
    private int imeBottom = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 内容铺到屏幕边缘，键盘由下面的 inset 监听自己处理
            WindowCompat.setDecorFitsSystemWindows(window, false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        } else {
            // 老系统拿不到 ime() inset，让窗口自己随键盘缩小
            WindowCompat.setDecorFitsSystemWindows(window, true);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            // 允许画进刘海区域，再用 displayCutout inset 把内容顶出来
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
        hideSystemBars();

        WebView webView = (bridge == null) ? null : bridge.getWebView();
        if (webView == null) {
            Log.e(TAG, "webView is null, shell features disabled");
        } else {
            webView.setBackgroundColor(0xFF0F0F0F);
            webView.addJavascriptInterface(new ShellBridge(this), "AndroidShell");
            webView.setDownloadListener(this::onDownloadRequest);
            applyKeyboardInsets(webView);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });
    }


    /** 隐藏状态栏 + 导航栏；上滑可临时呼出，几秒后自动再隐藏 */
    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    /**
     * 键盘适配。
     * decorFitsSystemWindows=false 时窗口不会随键盘缩放，所以手动把 WebView 的底部
     * 撑出 IME 的高度 —— 页面里的输入栏（fixed bottom）会随之整体上移，不会被键盘盖住。
     * 同时把刘海（displayCutout）的内边距补上，避免内容钻到摄像头孔下面。
     */
    private void applyKeyboardInsets(WebView webView) {
        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
            Insets cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            imeBottom = ime.bottom;
            int bottom = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ? ime.bottom : 0;
            v.setPadding(cut.left, cut.top, cut.right, bottom);
            return insets;
        });
    }

    /**
     * 返回：键盘 → 页面内层级 → 退出提示。
     * WebView 的 history 里存的是应用压的锚点（BackStack），goBack() 触发 popstate，
     * 应用自己会把弹窗/面板/侧栏/手机内页逐层收起，所以这里不需要懂业务。
     */
    private void handleBack() {
        WebView webView = (bridge == null) ? null : bridge.getWebView();

        if (webView != null && imeBottom > 0) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
            imeBottom = 0;
            return;
        }

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            // 退到后台而不是销毁：整页 20MB，重建一次要重新解析很久
            moveTaskToBack(true);
            return;
        }
        lastBackAt = now;
        Toast.makeText(this, "再按一次返回退出应用", Toast.LENGTH_SHORT).show();
    }

    /**
     * http(s) 下载走系统下载器。
     * blob: / data: 在 DownloadListener 里是拿不到内容的，那部分由 native-shell.js
     * 通过 AndroidShell 桥分块写进「下载/skynex」。
     */
    private void onDownloadRequest(String url, String userAgent, String contentDisposition,
                                   String mimetype, long contentLength) {
        if (url == null) return;
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Log.i(TAG, "skip blob/data download (handled by JS bridge)");
            return;
        }
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mimetype);
            req.setTitle(fileName);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "skynex/" + fileName);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) dm.enqueue(req);
            Toast.makeText(this, "开始下载：" + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "download failed", e);
            Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onResume() {
        super.onResume();
        hideSystemBars();
    }
}

