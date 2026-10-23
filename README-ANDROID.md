# skynex Android 版（Capacitor 壳 + GitHub Actions 云构建）

本仓库根目录的 `skynex文件版xxx.html` 仍是**唯一源码**，Android 版只是给它套了一个 WebView 壳，
Web 内容一行没改（只在打包时往 `</body>` 前插一个 `native-shell.js` 桥接脚本）。

## 一、怎么拿到 APK

两种方式，任选：

1. ** Releases（推荐，手机直接点）**
   打开 <https://github.com/Suzi190/000skynex/releases>，找 `skynex APK (latest)`，
   下载里面的 `skynex-debug.apk` 安装即可。
   每次 push 到 `main` 都会自动重新构建并覆盖这个 release 的 APK。

2. **Actions 里的构建产物**
   打开 <https://github.com/Suzi190/000skynex/actions/workflows/android-build.yml>，
   进最近一次成功的 run，页面底部 `Artifacts` → `skynex-debug-apk`（需要登录 GitHub）。

> 也可以手动触发：Actions → `Build Android APK` → `Run workflow`。

## 二、四个需求的实现位置

| 需求 | 实现 |
| --- | --- |
| 全屏沉浸式、隐藏状态栏 | `android/app/src/main/java/com/skynex/app/MainActivity.java` → `hideSystemBars()`：`WindowInsetsControllerCompat.hide(systemBars())` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（上滑可临时呼出）；`onWindowFocusChanged`/`onResume` 里重新隐藏。窗口背景色 `#0F0F0F`，刘海用 `displayCutout` inset 顶开，不会露白边。 |
| 键盘不挡输入框 | 同文件 `applyKeyboardInsets()`：API 30+ 用 `decorFitsSystemWindows=false` + 监听 `WindowInsetsCompat.Type.ime()`，键盘弹出时给 WebView 加底部 padding，页面里的 `position:fixed` 输入栏整体上移；API < 30（拿不到 ime inset）退回 `adjustResize` 让窗口跟随键盘缩放。`AndroidManifest.xml` 里也有 `windowSoftInputMode="adjustResize"` 兜底。 |
| 左→右滑返回上一页，而不是退出 | `handleBack()` + `getOnBackPressedDispatcher()`：系统返回键 / 全面屏边缘右滑返回 → 先看 `webView.canGoBack()`，有就 `goBack()`。应用自己用 `history` 锚点 + `popstate`（BackStack）实现了弹窗/面板/侧栏/手机内页的逐层返回，所以这里只需要走 history。真的退到底了才提示「再按一次返回退出应用」，2 秒内再按一次才 `moveTaskToBack`（退到后台，避免 20MB 页面重载）。键盘开着时返回键先收键盘。 |
| 应用图标 | `assets/icon-source.jpg` 是原图，`scripts/gen-assets.ps1` 生成 `mipmap-*/ic_launcher.png`、`ic_launcher_round.png`、`ic_launcher_foreground.png`（自适应图标，前景缩进 66% 安全区）以及各密度 `splash.png`（深色背景 + 圆角白底 logo）。 |

## 三、数据导入导出

WebView 不支持 `<a download>`，所以导出的 blob/dataURL 会被 `native/native-shell.js` 接管：

- 应用里点「保存到文件」→ 捕获阶段拦下这次 `a.click()` → 读 blob → 512KB 分块 → 通过
  `AndroidShell` 桥交给原生 → 写进系统「下载」目录的 **`Download/skynex/`**（API 29+ 走 MediaStore，不需要权限）。
- 保存成功会 toast 出完整路径；可在文件管理器「下载/skynex」里找到，再用应用内「导入」选回来。
- 导入走 `<input type="file">`（Capacitor 的文件选择器，系统 SAF），`.json` / `.json.gz` 都能选，
  应用内部按 gzip 魔数判断是否压缩，不依赖后缀或 MIME。
  注意：`Utils.pickFile` 对纯扩展名 `accept` 是**故意不设**的，避免安卓把文件灰掉选不中。

## 四、本地构建（可选）

需要 JDK 21 + Android SDK（`ANDROID_HOME`）：

```powershell
npm ci
npm run cap:copy        # 生成 www/ 并同步进 android/
cd android
.\gradlew.bat assembleDebug
# 产物：android\app\build\outputs\apk\debug\app-debug.apk
```

`npm run sync` 单独跑只会生成 `www/`；`scripts/sync-www.js` 会自动挑仓库根目录最新的
`skynex*.html`（排除 `.bak`），也可以用环境变量 `SKYNEX_SRC` 指定。

## 五、改点东西要注意

- **换 Web 内容**：直接改根目录的 `skynex文件版xxx.html`，push 后重跑构建即可（`www/` 不进 git）。
- **换图标**：替换 `assets/icon-source.jpg`，跑一次
  `powershell -ExecutionPolicy Bypass -File scripts/gen-assets.ps1`（需要 Windows，用 System.Drawing 生成 PNG），
  然后提交 `android/app/src/main/res/mipmap-*` 和 `drawable*/splash.png`。
- **版本号**：`android/app/build.gradle` 里的 `versionCode` / `versionName`。
- **调试**：`capacitor.config.json` 里 `webContentsDebuggingEnabled` 改 `true` 后，
  电脑 Chrome 打开 `chrome://inspect` 就能调试 WebView。
