#!/usr/bin/env node
// DataShareServer 协议自测：真实 WebSocket / HTTP，不模拟。
//
// 覆盖：info 的 token 校验与在线状态、客户端先于服务端、服务端上线、双向转发、
// peer join、broadcast、3MB 大帧、服务端离线时客户端被断开、错误 token 的 401、
// /__remote/listen 的壳端语义、以及（有外网时）首页代理。

const [, , BASE, WS, TOKEN] = process.argv;
if (!BASE || !WS || !TOKEN) {
  console.error('用法: node client.mjs <base> <ws> <token>');
  process.exit(2);
}

let passed = 0;
const failures = [];
function ok(cond, name, detail) {
  if (cond) {
    passed += 1;
    console.log(`  ok  ${name}`);
  } else {
    failures.push(name + (detail ? `：${detail}` : ''));
    console.log(`FAIL  ${name}${detail ? `：${detail}` : ''}`);
  }
}
function eq(actual, expected, name) {
  ok(actual === expected, name, `got ${JSON.stringify(actual)}`);
}

async function getJson(url) {
  const res = await fetch(url);
  return { status: res.status, body: await res.json() };
}

class Peer {
  constructor(role, name) {
    this.role = role;
    this.name = name;
    this.queue = [];
    this.waiters = [];
    this.closed = false;
    this.closeCode = 0;
  }
  open(timeout = 5000) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(WS);
      this.ws = ws;
      const timer = setTimeout(() => reject(new Error('open timeout')), timeout);
      ws.addEventListener('open', () => ws.send(JSON.stringify({ t: 'hello', role: this.role, token: TOKEN, name: this.name })));
      ws.addEventListener('message', (e) => {
        let msg;
        try {
          msg = JSON.parse(e.data);
        } catch {
          return;
        }
        if (msg.t === 'welcome') {
          clearTimeout(timer);
          this.id = msg.id;
          this.welcome = msg;
          resolve(msg);
        }
        this.push(msg);
      });
      ws.addEventListener('close', (e) => {
        this.closed = true;
        this.closeCode = e.code;
        this.push({ t: '__closed', code: e.code });
      });
      ws.addEventListener('error', () => {
        if (!this.id) {
          clearTimeout(timer);
          reject(new Error('ws error'));
        }
      });
    });
  }
  push(msg) {
    const at = this.waiters.findIndex((w) => w.pred(msg));
    if (at >= 0) {
      const [w] = this.waiters.splice(at, 1);
      clearTimeout(w.timer);
      w.resolve(msg);
    } else {
      this.queue.push(msg);
    }
  }
  next(pred, timeout = 5000) {
    const hit = this.queue.findIndex(pred);
    if (hit >= 0) return Promise.resolve(this.queue.splice(hit, 1)[0]);
    return new Promise((resolve, reject) => {
      const w = { pred, resolve };
      w.timer = setTimeout(() => {
        const i = this.waiters.indexOf(w);
        if (i >= 0) this.waiters.splice(i, 1);
        reject(new Error('message timeout'));
      }, timeout);
      this.waiters.push(w);
    });
  }
  send(obj) {
    this.ws.send(JSON.stringify(obj));
  }
  close() {
    try {
      this.ws.close();
    } catch {
      /* ignore */
    }
  }
}

async function main() {
  console.log(`DataShareServer 自测 @ ${BASE} token=${TOKEN}`);

  // ---- HTTP info ----
  const info = await getJson(`${BASE}/__remote/info?token=${TOKEN}`);
  eq(info.status, 200, 'info 返回 200');
  eq(info.body.ok, true, 'info.ok');
  eq(info.body.tokenOk, true, 'info.tokenOk=true');
  eq(info.body.hostOnline, false, '还没服务端时 hostOnline=false');
  eq(info.body.clients, 0, '还没客户端时 clients=0');
  ok(Array.isArray(info.body.addresses), 'info.addresses 是数组');

  const wrong = await getJson(`${BASE}/__remote/info?token=nope`);
  eq(wrong.body.tokenOk, false, '错误 token：tokenOk=false');
  const anon = await getJson(`${BASE}/__remote/info`);
  eq(anon.body.tokenOk, null, '不带 token：tokenOk=null');

  const listen = await fetch(`${BASE}/__remote/listen`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ port: 4173, token: TOKEN }),
  });
  const listenBody = await listen.json();
  eq(listenBody.ok, false, '壳端 /__remote/listen 明确拒绝（端口由壳控制）');
  ok(/端口/.test(listenBody.error || ''), '/__remote/listen 给出可读原因');

  // ---- 客户端先连：没有服务端 ----
  const c1 = new Peer('client', '手机A');
  const w1 = await c1.open();
  ok(/^c\d+$/.test(w1.id), '客户端拿到 cN 形式的 id');
  eq(w1.role, 'client', 'welcome.role=client');
  eq(w1.hostOnline, false, '客户端先到时 hostOnline=false');
  c1.send({ t: 'to', to: 'host', msg: { k: 'request-snapshot' } });
  const noHost = await c1.next((m) => m.t === 'error');
  ok(/服务端不在线/.test(noHost.error), '没有服务端时客户端收到「服务端不在线」');

  // ---- 服务端上线 ----
  const host = new Peer('host', '设备 ABC123');
  const wh = await host.open();
  eq(wh.id, 'host', '服务端 id=host');
  eq(wh.role, 'host', 'welcome.role=host');
  eq(wh.clients.length, 1, '服务端上线时看到已有 1 个客户端');
  eq(wh.clients[0].id, w1.id, '欢迎名单里是先前那个客户端');

  c1.send({ t: 'to', to: 'host', msg: { k: 'request-snapshot' } });
  const req = await host.next((m) => m.t === 'msg');
  eq(req.from, w1.id, '客户端消息带上来源 id');
  eq(req.msg.k, 'request-snapshot', '消息体原样转发');

  host.send({ t: 'to', to: w1.id, msg: { k: 'snapshot', version: 1, db: 'abc' } });
  const snap = await c1.next((m) => m.t === 'msg');
  eq(snap.from, 'host', '服务端消息 from=host');
  eq(snap.msg.version, 1, '快照版本原样到达');

  // ---- 第二个客户端：peer join ----
  const c2 = new Peer('client', '平板B');
  const w2 = await c2.open();
  const join = await host.next((m) => m.t === 'peer' && m.event === 'join');
  eq(join.id, w2.id, '新客户端加入：服务端收到 peer join');
  eq(join.name, '平板B', 'peer join 带设备名');

  // ---- broadcast ----
  host.send({ t: 'broadcast', msg: { k: 'snapshot', version: 2 } });
  const b1 = await c1.next((m) => m.t === 'msg' && m.msg.version === 2);
  const b2 = await c2.next((m) => m.t === 'msg' && m.msg.version === 2);
  eq(b1.msg.k, 'snapshot', 'broadcast 到客户端 1');
  eq(b2.msg.k, 'snapshot', 'broadcast 到客户端 2');

  // ---- 3MB 大帧 ----
  const big = 'x'.repeat(3 * 1024 * 1024);
  host.send({ t: 'broadcast', msg: { k: 'snapshot', version: 3, db: big } });
  const gotBig = await c1.next((m) => m.t === 'msg' && m.msg.version === 3, 15000);
  eq(gotBig.msg.db.length, big.length, '3MB 快照分帧读写字节数一致');

  // ---- 服务端离线：客户端必须被断开 ----
  host.close();
  const offline = await c1.next((m) => m.t === 'error' && /服务端已离线/.test(m.error));
  ok(!!offline, '服务端断开时客户端收到离线说明');
  await c1.next((m) => m.t === '__closed');
  await c2.next((m) => m.t === '__closed');
  ok(c1.closed && c2.closed, '服务端断开后两个客户端都被关闭');

  // ---- 错误 token 的 WebSocket 握手 ----
  const bad = new WebSocket(WS.replace(TOKEN, 'wrong-token'));
  const badResult = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve('timeout'), 4000);
    bad.addEventListener('open', () => {
      clearTimeout(timer);
      resolve('open');
    });
    bad.addEventListener('error', () => {
      clearTimeout(timer);
      resolve('error');
    });
    bad.addEventListener('close', () => {
      clearTimeout(timer);
      resolve('error');
    });
  });
  eq(badResult, 'error', '错误 token 的 WS 握手被拒');
  try {
    bad.close();
  } catch {
    /* ignore */
  }

  // ---- 静态代理（需要外网；不通不算失败，只报告）----
  try {
    const res = await fetch(`${BASE}/`, { redirect: 'manual' });
    const body = await res.text();
    if (res.status === 200 && /<html|<!doctype html/i.test(body)) {
      passed += 1;
      console.log('  ok  首页代理返回 HTML');
    } else {
      console.log(`SKIP 首页代理：本机取不到上游（HTTP ${res.status}）`);
    }
  } catch (e) {
    console.log(`SKIP 首页代理：${e.message}`);
  }

  console.log(`\n${passed} 项通过，${failures.length} 项失败`);
  if (failures.length) {
    for (const f of failures) console.log('  - ' + f);
    process.exit(1);
  }
}

main().catch((e) => {
  console.error('自测中断：', e);
  process.exit(1);
});
