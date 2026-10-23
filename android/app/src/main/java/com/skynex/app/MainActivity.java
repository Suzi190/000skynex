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
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;

/**
 * skynex 的 Android 壳。
 *
 * 三件事：
 *  1. 全屏沉浸式：decorFitsSystemWindows=false + 隐藏状态栏/导航栏（上滑临时呼出），
 *     WebView 铺满整屏（含刘海/挖孔区域，没有黑边）；
 *  2. 键盘不挡输入框：自己量键盘高度（API 30+ 用 ime() inset，老系统用
 *     getWindowVisibleDisplayFrame 差值），给 WebView 加 bottomMargin 让它真的变矮，
 *     网页 viewport 随之变小，输入框就被顶到键盘上方；
 *  3. 返回不退出应用：系统返回键 / 边缘右滑返回 → 先走 WebView 的 history（应用自己
 *     用 popstate 实现了逐层返回），真的没得退了才「再按一次退出」。
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "SkynexMain";
    private static final long EXIT_WINDOW_MS = 2000L;

    private long lastBackAt = 0L;
    /** 当前键盘高度（px）；0 = 键盘收起 */
    private int imeBottom = 0;
    /** 最近一次从 WindowInsets 拿到的 ime inset（API 30+ 精确值） */
    private int imeInsetBottom = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Window window = getWindow();

        // ── 真·全屏：内容一直画到屏幕最上沿（状态栏 / 刘海 / 挖孔区域）──
        // 注意：decorFitsSystemWindows=true 时系统会给窗口留出刘海高度的安全区，
        // 在刘海机上顶部就是一条黑边（1.0.1 的回归）。所以要 false，让 WebView 铺满整屏。
        // 代价是窗口不再随键盘缩放，键盘高度由 installKeyboardWatcher() 自己量：
        // API 30+ 取 ime() inset，老系统用 getWindowVisibleDisplayFrame() 差值，
        // 再给 WebView 加 bottomMargin（不能用 padding，padding 不改变网页 viewport）。
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

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
            installKeyboardWatcher(webView);
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
     * 键盘适配：量出键盘高度，给 WebView 加 bottomMargin，让它真的变矮。
     *
     * 为什么要自己量：decorFitsSystemWindows=false（真全屏）时窗口不会随键盘缩放，
     * 必须自己让出空间，否则输入框会被键盘盖住。
     * 为什么用 margin 不用 padding：WebView 的网页视口取自 View 的 width/height，
     * padding 只影响绘制，window.innerHeight 不变，页面根本不知道键盘来了。
     */
    private void installKeyboardWatcher(WebView webView) {
        View decor = getWindow().getDecorView();

        // API 30+：ime() inset 是最准的值（键盘正好抬起/收起时会派发）
        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                imeInsetBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            }
            v.post(this::updateKeyboardState);
            return insets;
        });
        ViewCompat.requestApplyInsets(webView);

        // 全版本兜底：布局变化时用可见区域反推键盘高度。
        // （老系统拿不到 ime() inset；个别 ROM 也不派发，靠这个补）
        decor.getViewTreeObserver().addOnGlobalLayoutListener(this::updateKeyboardState);
    }

    /** 量键盘高度 → 调整 WebView 高度。键盘弹出/收起、转屏、导航栏变化都会走到这里 */
    private void updateKeyboardState() {
        WebView webView = (bridge == null) ? null : bridge.getWebView();
        if (webView == null) return;
        View decor = getWindow().getDecorView();
        if (decor.getHeight() <= 0) return;

        int kb = imeInsetBottom;

        // getWindowVisibleDisplayFrame 给的是屏幕坐标，和 decor 的屏幕底边相减就是被挡住的高度
        Rect visible = new Rect();
        decor.getWindowVisibleDisplayFrame(visible);
        int[] loc = new int[2];
        decor.getLocationOnScreen(loc);
        int frameKb = Math.max(0, loc[1] + decor.getHeight() - visible.bottom);
        if (frameKb > kb) kb = frameKb;

        // 过滤噪声：手势条/导航栏这些残值通常 < 48dp，键盘至少 150dp
        if (kb < dp(80)) kb = 0;

        if (kb != imeBottom) Log.i(TAG, "keyboard=" + kb + " (inset=" + imeInsetBottom + ", frame=" + frameKb + ")");
        imeBottom = kb;
        setBottomMargin(webView, kb);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 改变 WebView 的真实高度（网页 viewport 会跟着变），而不是 padding */
    private void setBottomMargin(View view, int bottom) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        if (mlp.bottomMargin == bottom) return;
        mlp.bottomMargin = bottom;
        view.setLayoutParams(mlp);
    }

    /** 键盘是否在显示（返回键先用它收键盘，而不是直接退页面） */
    private boolean isKeyboardVisible() {
        return imeBottom > 0;
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
            // 立刻收起留白（insets 会再确认一次）
            imeBottom = 0;
            imeInsetBottom = 0;
            setBottomMargin(webView, 0);
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

