package com.skynex.app;

import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
    /** 见过的最大的 decorView 高度（全屏时的高度），用来判断键盘是否把窗口顶矮了 */
    private int maxDecorHeight = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Window window = getWindow();

        // ── 键盘避让：让窗口自己随键盘缩放（adjustResize） ──
        // 这是 WebView 上最可靠的路径：窗口真的变矮 → 网页 viewport 跟着变小 →
        // 聚焦的输入框由 WebView 自己滚进可视区。Android 15 会对 targetSdk>=35 的应用
        // 强制 edge-to-edge 从而废掉 adjustResize，这里用 res/values-v35/themes.xml 的
        // windowOptOutEdgeToEdgeEnforcement 显式退出（全屏靠 hide() 隐藏系统栏，不受影响）。
        WindowCompat.setDecorFitsSystemWindows(window, true);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            // 允许画进刘海区域（隐藏状态栏后内容能铺到屏幕最上沿，不被裁成一条黑边）
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }

        super.onCreate(savedInstanceState);
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
     * 键盘兜底（正常路径用不到，adjustResize 已经把窗口缩小了）。
     *
     * 少数情况窗口不会随键盘缩放：强制 edge-to-edge 生效的机型、个别 ROM 不支持
     * adjustResize 等。这时 ime inset 会给出键盘高度，给 WebView 加 bottomMargin
     * 让它真的变矮 —— 网页 viewport 随之变小，输入栏（fixed bottom）整体上移。
     *
     * 注意：这里绝对不能用 setPadding。WebView 的网页视口取自 View 的 width/height，
     * padding 只影响绘制区域，window.innerHeight 不会变，输入框照样被键盘盖住
     * （第一版就是用 padding，真机上完全没效果，这就是键盘挡输入框的原因）。
     */
    private void applyKeyboardInsets(WebView webView) {
        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            imeBottom = ime.bottom;
            final int bottom = ime.bottom;
            // 等这一帧布局稳定后再量高度，避免拿到过渡态
            v.post(() -> {
                if (bottom == 0) {
                    // 键盘收起时顺手记录全屏高度，返回键判断键盘是否弹出要用
                    int h = getWindow().getDecorView().getHeight();
                    if (h > maxDecorHeight) maxDecorHeight = h;
                }
                setBottomMargin(v, shouldCompensate(bottom) ? bottom : 0);
            });
            return insets;
        });
        ViewCompat.requestApplyInsets(webView);
    }

    /** 窗口没被 adjustResize 缩小、却又有键盘 inset 时，才需要我们自己补 */
    private boolean shouldCompensate(int imeBottom) {
        if (imeBottom <= 0) return false;
        View decor = getWindow().getDecorView();
        Rect visible = new Rect();
        decor.getWindowVisibleDisplayFrame(visible);
        // 窗口已经缩到键盘上方（decor 高度 == 可见区域高度）→ 系统让位了，不要再加，否则底部双倍空白
        return decor.getHeight() > visible.height() + 8;
    }

    /** 改变 WebView 的真实高度（网页 viewport 会跟着变），而不是 padding */
    private void setBottomMargin(View view, int bottom) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        if (mlp.bottomMargin == bottom) return;
        mlp.bottomMargin = bottom;
        view.setLayoutParams(mlp);
        Log.i(TAG, "webview bottomMargin=" + bottom);
    }

    /** 键盘是否真的在显示：优先看 ime inset，其次看窗口是不是被顶矮了（adjustResize 路径） */
    private boolean isKeyboardVisible() {
        if (imeBottom > 0) return true;
        View decor = getWindow().getDecorView();
        int h = decor.getHeight();
        if (h <= 0) return false;
        if (h > maxDecorHeight) {
            maxDecorHeight = h; // 全屏时的基准高度
            return false;
        }
        return maxDecorHeight - h > 60;
    }

    /**
     * 返回：键盘 → 页面内层级 → 退出提示。
     * WebView 的 history 里存的是应用压的锚点（BackStack），goBack() 触发 popstate，
     * 应用自己会把弹窗/面板/侧栏/手机内页逐层收起，所以这里不需要懂业务。
     */
    private void handleBack() {
        WebView webView = (bridge == null) ? null : bridge.getWebView();

        if (webView != null && isKeyboardVisible()) {
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

