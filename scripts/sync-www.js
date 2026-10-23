/**
 * 把单文件版 HTML 同步成 Capacitor 的 www/index.html。
 *
 * 为什么不在仓库里直接放 www/index.html：
 *   源文件有 20MB+，再拷一份进 git 会让仓库翻倍。www/ 走 .gitignore，构建时现生成。
 *
 * 做两件事：
 *   1. 复制源 HTML → www/index.html，并在 </body> 前插入 <script src="native-shell.js">。
 *      （原生壳的桥接脚本独立成文件，不改动源 HTML，源文件继续可以单独浏览器打开用。）
 *   2. 复制 native/native-shell.js → www/native-shell.js。
 *
 * 用法：
 *   node scripts/sync-www.js                 # 自动挑最新的 skynex*.html
 *   SKYNEX_SRC=skynex文件版111.html node scripts/sync-www.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const WWW = path.join(ROOT, 'www');
const SHELL_SRC = path.join(ROOT, 'native', 'native-shell.js');
const SHELL_OUT = path.join(WWW, 'native-shell.js');
const OUT_HTML = path.join(WWW, 'index.html');
const SHELL_TAG = '<script src="native-shell.js"></script>';

function pickSource() {
  const fromEnv = process.env.SKYNEX_SRC;
  if (fromEnv) {
    const p = path.isAbsolute(fromEnv) ? fromEnv : path.join(ROOT, fromEnv);
    if (!fs.existsSync(p)) throw new Error('SKYNEX_SRC 指向的文件不存在: ' + p);
    return p;
  }
  // 仓库根的 skynex*.html，排除 .bak / .backup 备份
  const cands = fs.readdirSync(ROOT)
    .filter(n => /^skynex.*\.html$/i.test(n) && !/\.(bak|backup)/i.test(n))
    .map(n => {
      const p = path.join(ROOT, n);
      return { p, t: fs.statSync(p).mtimeMs };
    })
    .sort((a, b) => b.t - a.t);
  if (!cands.length) throw new Error('没找到 skynex*.html 源文件');
  return cands[0].p;
}

function main() {
  const src = pickSource();
  const html = fs.readFileSync(src, 'utf8');

  fs.mkdirSync(WWW, { recursive: true });

  // 幂等：已经注入过就先摘掉，避免重复构建越插越多
  let out = html.split(SHELL_TAG).join('');
  const idx = out.lastIndexOf('</body>');
  if (idx === -1) {
    out += '\n' + SHELL_TAG + '\n';
  } else {
    out = out.slice(0, idx) + SHELL_TAG + '\n' + out.slice(idx);
  }

  fs.writeFileSync(OUT_HTML, out, 'utf8');
  fs.copyFileSync(SHELL_SRC, SHELL_OUT);

  const mb = (n) => (n / 1048576).toFixed(2) + ' MB';
  console.log('[sync-www] 源文件: ' + path.basename(src) + '  (' + mb(fs.statSync(src).size) + ')');
  console.log('[sync-www] 输出  : www/index.html  (' + mb(fs.statSync(OUT_HTML).size) + ')');
  console.log('[sync-www] 输出  : www/native-shell.js');
}

main();
