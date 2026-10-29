#!/usr/bin/env node
// 在桌面上跑壳里那页 assets/nutstore-test.html：假壳（net.*）+ 假坚果云，验页面本身写得对不对。
//
// 为什么需要它：这一页真正的用处在手机上（浏览器里打不开壳的 asset），但它的
// 请求拼装、base64 收发、multistatus 解析、失败时的说法，全都可以在桌面上先证一遍。
// 手机上只剩一件事要人眼确认——壳自己发得出去；页面逻辑错了不该让用户当调试器。
//
// 假壳装在页面里（addInitScript）：接 net.capabilities / net.request，用**原始 fetch**
// 代发。假壳跑在浏览器引擎里，自己受跨域约束，所以只能打在这套会回跨域头的假服务端上
// （tools/net-http-test/server.mjs 与 Java 侧共用一份，跨域头是为这件事加的）。
//
// 用法：node tools/nutstore-page-test/run.mjs [--big]
//   --big  顺带跑「加测 2MB 大文件」那档（2MB 过 base64，慢，但正是壳 12MB 上限的下方）
//
// 三组断言：
//   P1 有壳：一键跑完整套 → 失败 0；服务端确实收到 PROPFIND/MKCOL/PUT/GET/HEAD/DELETE，
//      且带 Depth、带正确的 Basic 头；页面的 window.fetch 一次都没被业务用到（全走桥）
//   P2 无壳：没有 window.AndroidBridge 时，页面 self-check 报错并把按钮全禁用
//   P3 壳报错：net.request 失败时，页面把壳那句人话原样显示（不套 CORS 说辞、不改写）

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

let chromium;
try {
  ({ chromium } = await import('/root/.nvm/versions/node/v22.13.1/lib/node_modules/playwright/index.mjs'));
} catch {
  ({ chromium } = await import('/usr/local/node/lib/node_modules/playwright/index.mjs'));
}

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(HERE, '..', 'net-http-test', 'server.mjs');
const PAGE = 'file://' + path.resolve(HERE, '..', '..', 'app', 'src', 'main', 'assets', 'nutstore-test.html');
const PORT = 8091; // 与 Java 自测的 8078 错开，两边可以同时跑
const BASE = `http://127.0.0.1:${PORT}`;
const WITH_BIG = process.argv.includes('--big');

const failures = [];
const check = (label, cond, extra = '') => {
  if (cond) console.log(`  ✓ ${label}`);
  else {
    console.log(`  ✗ ${label}${extra ? ` — ${extra}` : ''}`);
    failures.push(label);
  }
};

// ---------- 假坚果云（复用 Java 侧那一份） ----------
const srv = spawn(process.execPath, [SERVER, String(PORT)], { stdio: ['ignore', 'pipe', 'pipe'] });
srv.stdout.on('data', (d) => process.stdout.write('  [fake] ' + d));
srv.stderr.on('data', (d) => process.stderr.write('  [fake!] ' + d));
const stop = () => {
  try { srv.kill(); } catch {}
};
process.on('exit', stop);

async function waitUp() {
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch(`${BASE}/__config`);
      if (r.ok) return r.json();
    } catch {}
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error('假坚果云起不来');
}
const reset = () => fetch(`${BASE}/__reset`, { method: 'POST' });
const getLog = async () => (await (await fetch(`${BASE}/__log`)).json());
const getFiles = async () => (await (await fetch(`${BASE}/__files`)).json());

// ---------- 假壳 ----------
/**
 * bridge=true：有壳，接住 net.*，并用原始 fetch 代发；boom=true：net.request 一律失败，
 * 用来验「壳报错时页面怎么说」。页面自己的 window.fetch 会被包一层计数，
 * 于是「请求到底走没走桥」是可数的。
 */
const SHELL_JS = (bridge, boom) => `
(function () {
  var rawFetch = window.fetch.bind(window);
  window.__pageFetch = 0;
  window.__netCalls = [];
  window.fetch = function (input, init) {
    window.__pageFetch++;
    return rawFetch(input, init);
  };
  if (!${bridge}) return;
  window.AndroidBridge = {
    getEnv: function () {
      return JSON.stringify({
        app: '朝汇夕思', version: '1.2.0', builtAt: '2026-10-25 18:10', commit: 'e26dfad',
        platform: 'android', isApp: true, bridge: 'jsi-v1', proto: 1,
      });
    },
  };
  var CAPS = {
    methods: { GET: true, PUT: true, DELETE: true, MKCOL: true, PROPFIND: true, PROPPATCH: true,
      COPY: true, MOVE: true, LOCK: true, UNLOCK: true, HEAD: true, OPTIONS: true, PATCH: true, POST: true },
    maxRequestBytes: 12 * 1024 * 1024, maxResponseBytes: 32 * 1024 * 1024,
    maxTimeoutMs: 120000, version: '1.2.0',
  };
  function b64bytes(t) {
    var bin = atob(t.replace(/\\s+/g, '')), out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function bytesB64(u8) {
    var s = '', step = 8192;
    for (var i = 0; i < u8.length; i += step) s += String.fromCharCode.apply(null, u8.subarray(i, i + step));
    return btoa(s);
  }
  window.addEventListener('message', function (e) {
    var m = e.data;
    if (!m || m.type !== 'gv-bridge-request') return;
    var reply = function (ok, result, error) {
      window.postMessage({ type: 'gv-bridge-response', id: m.id, ok: ok, result: result, error: error }, '*');
    };
    if (m.action === 'net.capabilities') { reply(true, CAPS, null); return; }
    if (m.action !== 'net.request') { reply(false, null, 'unknown net action: ' + m.action); return; }
    var p = m.params || {};
    window.__netCalls.push({ method: p.method, url: p.url, depth: (p.headers || {}).Depth || '' });
    if (${boom ? 'true' : 'false'}) {
      // 假壳模拟壳里那两条常见失败：连接超时（壳自己报的人话）
      reply(false, null, '坚果云响应超时，检查一下网络再试');
      return;
    }
    var init = { method: p.method, headers: p.headers || {} };
    if (p.bodyBase64) init.body = b64bytes(p.bodyBase64);
    rawFetch(p.url, init)
      .then(async function (r) {
        var buf = new Uint8Array(await r.arrayBuffer());
        var headers = {};
        r.headers.forEach(function (v, k) { headers[k.toLowerCase()] = v; });
        reply(true, {
          status: r.status, statusText: r.statusText || '', headers: headers,
          bodyLength: buf.length, bodyBase64: bytesB64(buf),
        }, null);
      })
      .catch(function (err) { reply(false, null, '请求发不出去：' + err.message); });
  });
})();
`;

const mkPage = async (browser, bridge, boom) => {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 780 } });
  await ctx.addInitScript(SHELL_JS(bridge, boom));
  const p = await ctx.newPage();
  const pageErrors = [];
  p.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 200)));
  await p.goto(PAGE, { waitUntil: 'domcontentloaded' });
  await p.waitForFunction(() => document.querySelectorAll('.line').length >= 0);
  return { ctx, p, pageErrors };
};

const readState = (p) =>
  p.evaluate(() => ({
    summary: document.getElementById('summary').textContent.replace(/\s+/g, ' ').trim(),
    env: document.getElementById('env').textContent,
    lines: [...document.querySelectorAll('#log .line')].map((el) => ({
      cls: el.className.replace('line ', ''),
      text: el.textContent.replace(/\s+/g, ' ').trim(),
    })),
    disabled: [...document.querySelectorAll('button')].map((b) => b.disabled).filter(Boolean).length,
    total: document.querySelectorAll('button').length,
    fetch: window.__pageFetch,
    calls: window.__netCalls,
  }));

const waitDone = async (p, ms) => {
  await p.waitForFunction(
    () => /^跑完/.test(document.getElementById('summary').textContent.trim()),
    null,
    { timeout: ms },
  );
};

// ---------- 开跑 ----------
const cfg = await waitUp();
console.log(`假坚果云：${BASE}（账号 ${cfg.user}）`);

const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM || (fs.existsSync('/usr/bin/chromium') ? '/usr/bin/chromium' : undefined),
  // 不加 --disable-web-security：跨域头有没有配齐，正是这套断言要盯的东西，
  // 屏蔽掉安全检查就等于把唯一会失败的环节藏起来了。
  args: ['--no-sandbox', '--allow-file-access-from-files'],
});

try {
  // ===== P1 有壳：整套跑通 =====
  console.log('\nP1 壳在，一键跑完整套');
  await reset();
  {
    const { ctx, p, pageErrors } = await mkPage(browser, true, false);
    await p.fill('#in-base', BASE + '/dav');
    await p.fill('#in-user', cfg.user);
    await p.fill('#in-pass', cfg.pass);
    await p.fill('#in-folder', 'DatingRecord-ShellTest');

    check('P1.1 环境行读到了壳的版本（getEnv 生效）', /壳 朝汇夕思 v1\.2\.0/.test((await readState(p)).env), (await readState(p)).env);
    check('P1.2 无壳时才会报的那句没出现（说明桥是通的）', !/不在壳里打开/.test((await readState(p)).env + (await readState(p)).lines.map((l) => l.text).join('|')));

    await p.click('#btn-all');
    await waitDone(p, 120000);
    const st = await readState(p);
    const fails = st.lines.filter((l) => l.cls.includes('fail'));
    check('P1.3 一键跑完：失败 0', /失败 0|失败 <b>0<\/b>/.test(st.summary) && !fails.length, st.summary + ' | ' + fails.map((f) => f.text).join(' / '));
    check('P1.4 结论行在（全绿的最后一句）', st.lines.some((l) => /✓ 结论/.test(l.text)));
    const capsLine = st.lines.find((l) => /探测能力/.test(l.text));
    check('P1.5 探测能力报出 PROPFIND / MKCOL 与三条上限', !!capsLine && /PROPFIND/.test(capsLine.text) && /12\.00 MB/.test(capsLine.text) && /32\.00 MB/.test(capsLine.text) && /120s/.test(capsLine.text), capsLine ? capsLine.text : '没有这一行');
    check('P1.6 业务请求一次都没用页面 fetch（全交给桥）', st.fetch === 0, 'window.fetch 被调用 ' + st.fetch + ' 次');
    // 只有 net.request 计数：anon1 + auth1 + mkcol2 + text2 + bin2 + list1 + head1 + clean4 = 14
    // （net.capabilities 不算，探测只问一次是另一条断言）
    check('P1.7 桥收到的请求条数对得上（14 次，全带 method）', st.calls.length === 14 && st.calls.every((c) => c.method), 'calls=' + JSON.stringify(st.calls).slice(0, 300));

    const serverLog = await getLog();
    const verbs = serverLog.map((l) => l.split(' ')[0]);
    for (const v of ['PROPFIND', 'MKCOL', 'PUT', 'GET', 'HEAD', 'DELETE']) {
      check(`P1.8 服务端收到过 ${v}`, verbs.includes(v), serverLog.join(' | '));
    }
    check('P1.9 PROPFIND 带 Depth 头（不是被剪掉的空请求）', st.calls.some((c) => c.method === 'PROPFIND' && c.depth), JSON.stringify(st.calls).slice(0, 300));
    check('P1.10 中文路径按百分号编码送达服务端', serverLog.some((l) => /hello-%E4%B8%AD%E6%96%87/.test(l)), serverLog.join(' | '));
    const filesAfter = await getFiles();
    check('P1.11 清理步骤真把测试文件删了（服务端只剩目录）', filesAfter.every((f) => !/blob-|hello-/.test(f)), filesAfter.join(' | '));
    check('P1.12 页面运行期零报错', pageErrors.length === 0, pageErrors.join(' | '));

    // 64KB 那一步的输出要能报出字节数与耗时（用户靠它判断慢不慢）
    const binLine = st.lines.find((l) => /64KB 二进制读写/.test(l.text));
    check('P1.13 二进制那步报出逐字节相同与耗时', !!binLine && /逐字节相同/.test(binLine.text) && /ms/.test(binLine.text), binLine ? binLine.text : '没有这一行');

    if (WITH_BIG) {
      await reset();
      await p.click('#btn-big');
      await waitDone(p, 240000);
      const st2 = await readState(p);
      const big = st2.lines.find((l) => l.cls.includes('ok') && /2MB 大文件读写/.test(l.text));
      check('P1.14 2MB 过桥逐字节相同（--big）', !!big && /逐字节相同/.test(big.text), st2.summary + ' | ' + (big ? big.text : '没有通过的那一行'));
    } else {
      console.log('  · P1.14 2MB 大文件：跳过（加 --big 才跑）');
    }
    await ctx.close();
  }

  // ===== P2 无壳：页面自己该说清测不了 =====
  console.log('\nP2 不在壳里打开');
  {
    const { ctx, p } = await mkPage(browser, false, false);
    const st = await readState(p);
    check('P2.1 报出「没有 window.AndroidBridge」', st.lines.some((l) => /✗ 环境/.test(l.text) && /AndroidBridge/.test(l.text)), st.lines.map((l) => l.text).join(' § '));
    check('P2.2 所有按钮被禁用（不让用户点了半天全超时）', st.disabled === st.total && st.total > 5, st.disabled + '/' + st.total);
    check('P2.3 提示里给出正确打开方式（长按顶部空白）', st.lines.some((l) => /长按|adb/.test(l.text)));
    await ctx.close();
  }

  // ===== P3 壳报错原样上抛 =====
  console.log('\nP3 壳里发不出去时说什么');
  {
    const { ctx, p } = await mkPage(browser, true, true);
    await p.fill('#in-base', BASE + '/dav');
    await p.fill('#in-user', cfg.user);
    await p.fill('#in-pass', cfg.pass);
    await p.click('[data-step="anon"]');
    await p.waitForFunction(() => /✗ 不带凭据/.test(document.getElementById('log').textContent), null, { timeout: 60000 });
    const st = await readState(p);
    const anon = st.lines.find((l) => /✗ 不带凭据/.test(l.text));
    check('P3.1 显示的是壳那句原文（没被改写成跨域说辞）', !!anon && /坚果云响应超时，检查一下网络再试/.test(anon.text), anon ? anon.text : st.lines.map((l) => l.text).join(' § '));
    check('P3.2 没有把「装壳 / 反向代理」的兜底话术塞进来', !!anon && !/跨域|反向代理/.test(anon.text), anon ? anon.text : '');
    await ctx.close();
  }
} finally {
  await browser.close().catch(() => {});
  stop();
}

console.log('');
if (failures.length) {
  console.log(`FAIL: ${failures.length} 项不通`);
  failures.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}
console.log(`PASS: nutstore-test.html 页面自测全部通过（${WITH_BIG ? '含' : '不含'} 2MB）`);
