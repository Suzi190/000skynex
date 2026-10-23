/**
 * skynex 原生壳（Android WebView）注入脚本
 * ------------------------------------------------------------------
 * 只在 APK 里生效：浏览器里打开单文件 HTML 时 window.AndroidShell 不存在，
 * 本脚本直接 return，不改变任何原有行为。
 *
 * 解决的核心问题：WebView 不支持 <a download>。
 * 应用的导出（存档 JSON / json.gz / 图片 / 状态栏主题）全都走
 * 「URL.createObjectURL(blob) + a.download + a.click()」这一套，在 WebView 里点了没反应。
 * 这里在捕获阶段劫持这类点击，把数据分块交给原生层写进系统「下载」目录（MediaStore），
 * 用户能在文件管理器里看到、也能再用「导入」选回来。
 *
 * 关于「必须在用户手势里 a.click()」的坑：应用已经处理好了（saveFile 面板里同步点）。
 * 这里只做数据搬运，不再触发任何 DOM 下载。
 */
(function () {
  'use strict';

  var API = window.AndroidShell;
  if (!API || typeof API.saveStart !== 'function') return; // 非 APK 环境，什么都不做

  var CHUNK = 512 * 1024; // 单次跨桥字节数上限：base64 后 ~683KB，避免超大字符串卡死主线程

  function u8ToB64(u8) {
    var out = '';
    var step = 0x8000;
    for (var i = 0; i < u8.length; i += step) {
      out += String.fromCharCode.apply(null, u8.subarray(i, i + step));
    }
    return btoa(out);
  }

  function dataUrlToBytes(dataUrl) {
    var m = String(dataUrl || '').match(/^data:([^;,]*)(;base64)?,([\s\S]*)$/);
    if (!m) return null;
    var mime = m[1] || 'application/octet-stream';
    if (m[2]) {
      var bin = atob(m[3]);
      var u8 = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
      return { mime: mime, bytes: u8 };
    }
    var text = decodeURIComponent(m[3]);
    var enc = new TextEncoder().encode(text);
    return { mime: mime, bytes: enc };
  }

  /** 分块写入系统下载目录；返回 true=成功 */
  function writeFile(name, mime, bytes) {
    var ok = false;
    try {
      ok = !!API.saveStart(String(name || 'download'), String(mime || 'application/octet-stream'));
    } catch (_) {
      ok = false;
    }
    if (!ok) {
      try { API.toast('保存失败：无法创建文件'); } catch (_) {}
      return false;
    }
    try {
      for (var off = 0; off < bytes.length; off += CHUNK) {
        API.saveChunk(u8ToB64(bytes.subarray(off, off + CHUNK)));
      }
      return !!API.saveFinish();
    } catch (err) {
      try { API.saveAbort(); } catch (_) {}
      try { API.toast('保存失败：' + (err && err.message ? err.message : '未知错误')); } catch (_) {}
      return false;
    }
  }

  function guessName(a, mime) {
    var name = a.getAttribute('download') || '';
    if (name) return name;
    var ext = /png/i.test(mime) ? '.png' : (/jpe?g/i.test(mime) ? '.jpg' : (/gzip/i.test(mime) ? '.json.gz' : '.bin'));
    return 'skynex-' + Date.now() + ext;
  }

  function handleAnchor(a) {
    var href = a.getAttribute('href') || '';
    var name = a.getAttribute('download') || '';

    if (/^blob:/i.test(href)) {
      // 应用会把 blob: 链接在 4 秒后 revoke，所以这里立刻 fetch（不等任何 await 链外的延时）
      return fetch(href)
        .then(function (r) { return r.blob(); })
        .then(function (blob) {
          return blob.arrayBuffer().then(function (buf) {
            writeFile(name || guessName(a, blob.type), blob.type, new Uint8Array(buf));
          });
        });
    }

    if (/^data:/i.test(href)) {
      var parsed = dataUrlToBytes(href);
      if (parsed) writeFile(name || guessName(a, parsed.mime), parsed.mime, parsed.bytes);
      return Promise.resolve();
    }

    // http(s) 链接不在这里处理，交给原生 DownloadListener
    return Promise.resolve();
  }

  // 捕获阶段劫持：a.click() 是程序化点击，同样会走到这里
  document.addEventListener('click', function (e) {
    var t = e.target;
    var a = t && t.closest ? t.closest('a[download]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (!/^(blob|data):/i.test(href)) return;
    e.preventDefault();
    e.stopPropagation();
    try {
      handleAnchor(a).catch(function (err) {
        try { API.toast('保存失败：' + (err && err.message ? err.message : '读取数据出错')); } catch (_) {}
      });
    } catch (err) {
      try { API.toast('保存失败'); } catch (_) {}
    }
  }, true);

  // 兜底：主页面跑起来后告诉原生「壳已就绪」（原生用来判断是内嵌环境）
  try { API.ready(); } catch (_) {}

  // 让原生能拿到未捕获异常，方便 logcat 里定位问题
  window.addEventListener('error', function (ev) {
    try {
      API.log('[web] ' + (ev.message || 'error') + ' @' + (ev.filename || '?') + ':' + (ev.lineno || 0));
    } catch (_) {}
  });
})();
