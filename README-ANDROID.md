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

> **升级注意**：1.0.0 是 CI 每次随机签名的，和现在的包对不上，**只影响 1.0.0 → 1.0.1 这一次**
> （需要先卸载旧版再装）。1.0.1 起签名固定在仓库的 `android/app/debug.keystore`（CI 首次构建时生成并提交），
> 所以 **1.0.1 / 1.0.2 / 1.0.3 → 1.0.4 直接覆盖安装就行**，存档不会丢。
> 还停在 1.0.0 的话，请先在应用内「导出」到 `下载/skynex/`，装好新版再「导入」。

## 二、四个需求的实现位置

| 需求 | 实现 |
| --- | --- |
| 全屏沉浸式、隐藏状态栏 | `android/app/src/main/java/com/skynex/app/MainActivity.java` → `hideSystemBars()`：`WindowInsetsControllerCompat.hide(systemBars())` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（上滑可临时呼出）；`onWindowFocusChanged`/`onResume` 里重新隐藏。窗口背景 `#0F0F0F` 兜底，WebView 直接铺满整屏（含状态栏/刘海区域），不额外让出安全区。 |
| 真全屏（无黑边） | `decorFitsSystemWindows=false` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` + `WindowInsetsControllerCompat.hide(systemBars())`，并让 WebView 直接铺满整屏（含状态栏/刘海区域）。注意 `decorFitsSystemWindows=true` 会退化成「刘海高度的安全区」，看起来就是顶部一条黑边。 |
| 键盘不挡输入框 | `installKeyboardWatcher()` 自己量键盘高度：API 30+ 取 `WindowInsetsCompat.Type.ime()`，老系统用 `getWindowVisibleDisplayFrame()` 与 decor 底边相减；然后把高度以 **`bottomMargin`** 加到 WebView 上（**不能用 padding**：WebView 的网页视口取自 View 的 width/height，padding 只影响绘制，`window.innerHeight` 不变，页面感知不到键盘——1.0.0 就是这么踩坑的）。WebView 真的变矮后 viewport 跟着变小，输入框被顶到键盘上方。 |
| 键盘弹出不闪黑边 | 1.0.3 起 margin 变化走动画（`animateBottomMargin()`）：系统键盘滑上来约 250ms，如果 WebView 一次性缩到位，输入框会「嗖」地跳上去、键盘还没盖住，中间露一块纯黑。现在弹出时 WebView 用 300ms **线性**缩，始终缩得比键盘慢，底边一直压在键盘顶边之上，黑块露不出来；收起时立刻恢复全高（多出来的那块显示的是页面自己的背景，不会黑）。动画进行中收到的测量抖动（≤24dp）不会打断动画。 |
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

## 六、预设包：一套提示词 + 配套正则，整包切换

以前从酒馆（SillyTavern）导入预设时，所有提示词和所有正则都躺在同一堆里，互相覆盖、也没法整包切换。
现在一次导入＝一个**预设包**，包名取导入文件的文件名（截 30 字）：

- 提示词 → 分组名 = 文件名，并带 `st.preset` 归属标记；空内容/占位（marker）仍然跳过。
- 同一文件里夹带的正则（`regex_scripts` / `extensions.regex_scripts`）→ 打上 `pkg` = 文件名。
- 包里在酒馆本来就关着的提示词、本来就停用的正则，切过来时**保持关闭**。

**怎么切**：提示词页顶部那条预设栏（或右上角 ⋮ → 「切换预设」）→ 选一个包：

- 本包的提示词 / 正则打开，其它包的全部关闭；
- **手写的提示词、手写的正则完全不动**（没有 `st.preset` / `pkg` 归属，永远不会被预设连坐）；
- 导入完会顺手问一句「切换到「X」？」；选取消＝先存着，之后随时切；
- 预设栏上会显示「N 条提示词 · M 条正则」。

**正则页**新增来源筛选：`全部` / `手写/无来源` / `📦 文件名`，每条规则也会带一个 `📦 来源` 小标签，
不再是所有预设的正则糊成一条长列表；停用的规则只有切到它所属的包时才会被打开。

其它细节：

- 预设选择器里每行右侧的按钮：`🔄` 用当前开关状态刷新这条预设（改过包里的开关后同步回去）、重命名、删除。
  删除时会再问一句要不要把包里 N 条提示词 + M 条正则**一起删**（默认只删预设记录，内容留在库里）。
- 本次更新之前导入的老数据，第一次打开预设选择器时会按分组自动补成可切换的条目，不用重新导入。
- 单独导入「纯正则脚本文件」（不是预设）时只入库成规则、不建预设包，因此不会被来回切——想随包切换就把正则放进预设里一起导。
