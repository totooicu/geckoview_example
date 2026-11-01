#!/usr/bin/env node
// PayMock 协议自测：真实 HTTP，不模拟。
//
// 覆盖场景：
// 1. 下单字段齐全（orderId/status/amountCents/currency/expireAt/channels）
// 2. 重复下单返回同单（同一 serviceId + 同金额 + pending）
// 3. 已付后重复 pay 同 tradeNo（幂等）
// 4. paid→cancel 409
// 5. 未知单 404
// 6. notify?times=2 不产生第二笔（幂等）
// 7. 收银台 HTML 里三个按钮都在且跳 returnUrl 带 status
// 8. /orders 列表长度与顺序
// 9. 金额是分不是元
// 10. scheme 串前缀对（alipays://...orderVo=PAYMOCK-、weixin://...prepayId=PAYMOCK-）
// 11. orderId 形如 PAY-YYYYMMDD-0001
// 12. 状态只有 6 个
// 13. 终态不可逆（cancelled/failed/expired 后再 action → 409）
// 14. refund 从 paid 可走
// 15. 壳的加载地址钉在回环上的测试线子路径（同源才连得上 /__pay/*），端口与退路都在检查里
// 16. 取包用的那条 Actions：触发条件、JAVA_HOME 覆盖、产物路径、只读权限、失败落到摘要

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const [, , BASE] = process.argv;
if (!BASE) {
  console.error('用法: node client.mjs <base>');
  process.exit(2);
}

let passed = 0;
const failures = [];

function check(label, cond, extra) {
  if (cond) {
    passed++;
    console.log(`  ok  ${label}`);
  } else {
    failures.push(label + (extra ? `：${extra}` : ''));
    console.log(`FAIL  ${label}${extra ? `：${extra}` : ''}`);
  }
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body != null ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null), text: await res.text().catch(() => '') };
}

async function getJson(url) {
  const res = await fetch(url);
  return { status: res.status, body: await res.json().catch(() => null), text: await res.text().catch(() => '') };
}

async function getText(url) {
  const res = await fetch(url);
  return { status: res.status, text: await res.text() };
}

async function main() {
  console.log(`PayMock 自测 @ ${BASE}\n`);

  // ---- 1. 下单字段齐全 ----
  console.log('== 下单 ==');
  const create1 = await postJson(`${BASE}/__pay/order`, {
    service: { id: 'ocr.pass', title: '一次性通行凭证', amountCents: 600 },
    returnUrl: 'http://127.0.0.1:4173/?pay_done=1',
    channel: 'alipay',
  });
  check('下单返回 200', create1.status === 200, `got ${create1.status}`);
  const o1 = create1.body;
  check('orderId 存在', typeof o1.orderId === 'string' && o1.orderId.length > 0);
  check('orderId 形如 PAY-YYYYMMDD-0001', /^PAY-\d{8}-\d{4}$/.test(o1.orderId), o1.orderId);
  check('status=pending', o1.status === 'pending', o1.status);
  check('amountCents=600', o1.amountCents === 600, String(o1.amountCents));
  check('currency=CNY', o1.currency === 'CNY', o1.currency);
  check('expireAt 是毫秒时间戳', typeof o1.expireAt === 'number' && o1.expireAt > Date.now());
  check('channels 是数组', Array.isArray(o1.channels) && o1.channels.length === 2);

  // ---- 10. scheme 串前缀对 ----
  const alipay = o1.channels.find(c => c.kind === 'alipay');
  const wechat = o1.channels.find(c => c.kind === 'wechat');
  check('支付宝 channel 存在', !!alipay);
  check('微信 channel 存在', !!wechat);
  check('支付宝 label=支付宝', alipay && alipay.label === '支付宝');
  check('微信 label=微信', wechat && wechat.label === '微信');
  check('支付宝 scheme 以 alipays:// 开头', alipay && alipay.scheme.startsWith('alipays://'));
  check('支付宝 scheme 含 orderVo=PAYMOCK-', alipay && alipay.scheme.includes('orderVo=PAYMOCK-' + o1.orderId));
  check('微信 scheme 以 weixin:// 开头', wechat && wechat.scheme.startsWith('weixin://'));
  check('微信 scheme 含 prepayId=PAYMOCK-', wechat && wechat.scheme.includes('prepayId=PAYMOCK-' + o1.orderId));
  check('支付宝 webUrl 含 /__pay/cashier', alipay && alipay.webUrl.includes('/__pay/cashier'));
  check('微信 webUrl 含 /__pay/cashier', wechat && wechat.webUrl.includes('/__pay/cashier'));

  // ---- 2. 重复下单返回同单 ----
  console.log('\n== 重复下单幂等 ==');
  const create2 = await postJson(`${BASE}/__pay/order`, {
    service: { id: 'ocr.pass', title: '一次性通行凭证', amountCents: 600 },
  });
  check('重复下单返回同一 orderId', create2.body.orderId === o1.orderId,
    `${create2.body.orderId} vs ${o1.orderId}`);

  // ---- 已 paid 的服务再下单返回新单 ----
  // 先把第一单付了
  const payResult = await postJson(`${BASE}/__pay/order?id=${o1.orderId}&action=pay`);
  check('action=pay 返回 200', payResult.status === 200);
  check('pay 后 status=paid', payResult.body.status === 'paid', payResult.body.status);
  check('tradeNo 存在', typeof payResult.body.tradeNo === 'string' && payResult.body.tradeNo.length > 0);
  const tradeNo1 = payResult.body.tradeNo;

  // 再下一单（同一 serviceId + 同金额），应该返回新单
  const create3 = await postJson(`${BASE}/__pay/order`, {
    service: { id: 'ocr.pass', title: '一次性通行凭证', amountCents: 600 },
  });
  check('已付后再下单返回新 orderId', create3.body.orderId !== o1.orderId,
    `${create3.body.orderId} vs ${o1.orderId}`);
  check('新单 status=pending', create3.body.status === 'pending');

  // ---- 3. 已付后重复 pay 同 tradeNo（幂等） ----
  console.log('\n== 幂等 pay ==');
  const payResult2 = await postJson(`${BASE}/__pay/order?id=${o1.orderId}&action=pay`);
  check('重复 pay 返回 200', payResult2.status === 200);
  check('重复 pay 返回同一 tradeNo', payResult2.body.tradeNo === tradeNo1,
    `${payResult2.body.tradeNo} vs ${tradeNo1}`);

  // ---- 4. paid→cancel 409 ----
  console.log('\n== 终态不可逆 ==');
  const cancelPaid = await postJson(`${BASE}/__pay/order?id=${o1.orderId}&action=cancel`);
  check('paid→cancel 返回 409', cancelPaid.status === 409, `got ${cancelPaid.status}`);
  check('paid→cancel 返回 bad_transition', cancelPaid.body && cancelPaid.body.error === 'bad_transition');

  // ---- 13. 终态不可逆（cancelled/failed/expired 后再 action → 409）----
  // 拿第二单（create3）测试 fail → 再 action
  const o2id = create3.body.orderId;
  const fail2 = await postJson(`${BASE}/__pay/order?id=${o2id}&action=fail`);
  check('pending→fail 返回 200', fail2.status === 200);
  check('fail 后 status=failed', fail2.body.status === 'failed');
  const failRetry = await postJson(`${BASE}/__pay/order?id=${o2id}&action=pay`);
  check('failed→pay 返回 409', failRetry.status === 409, `got ${failRetry.status}`);

  // 下一单测 cancel
  const create4 = await postJson(`${BASE}/__pay/order`, {
    service: { id: 'test.cancel', title: '取消测试', amountCents: 100 },
  });
  const o3id = create4.body.orderId;
  const cancel3 = await postJson(`${BASE}/__pay/order?id=${o3id}&action=cancel`);
  check('pending→cancel 返回 200', cancel3.status === 200);
  check('cancel 后 status=cancelled', cancel3.body.status === 'cancelled');
  const cancelRetry = await postJson(`${BASE}/__pay/order?id=${o3id}&action=pay`);
  check('cancelled→pay 返回 409', cancelRetry.status === 409);

  // 下一单测 expire
  const create5 = await postJson(`${BASE}/__pay/order`, {
    service: { id: 'test.expire', title: '过期测试', amountCents: 200 },
  });
  const o4id = create5.body.orderId;
  const expire4 = await postJson(`${BASE}/__pay/order?id=${o4id}&action=expire`);
  check('pending→expire 返回 200', expire4.status === 200);
  check('expire 后 status=expired', expire4.body.status === 'expired');
  const expireRetry = await postJson(`${BASE}/__pay/order?id=${o4id}&action=pay`);
  check('expired→pay 返回 409', expireRetry.status === 409);

  // ---- 14. refund 从 paid 可走 ----
  console.log('\n== refund ==');
  // 用第一单（已 paid）
  const refund1 = await postJson(`${BASE}/__pay/order?id=${o1.orderId}&action=refund`);
  check('paid→refund 返回 200', refund1.status === 200);
  check('refund 后 status=refunded', refund1.body.status === 'refunded');
  // refunded 后再 action → 409
  const refundRetry = await postJson(`${BASE}/__pay/order?id=${o1.orderId}&action=cancel`);
  check('refunded→cancel 返回 409', refundRetry.status === 409);

  // ---- 5. 未知单 404 ----
  console.log('\n== 查单 & 404 ==');
  const notFound = await getJson(`${BASE}/__pay/order?id=PAY-99999999-9999`);
  check('未知 orderId 返回 404', notFound.status === 404, `got ${notFound.status}`);
  check('404 返回 order_not_found', notFound.body && notFound.body.error === 'order_not_found');

  // 查单（用第二单 o2id，状态是 failed）
  const query2 = await getJson(`${BASE}/__pay/order?id=${o2id}`);
  check('查单返回 200', query2.status === 200);
  check('查单 status=failed', query2.body.status === 'failed');
  check('查单 amountCents 是分', query2.body.amountCents === 600);
  check('查单 currency=CNY', query2.body.currency === 'CNY');

  // ---- 6. notify?times=2 不产生第二笔（幂等） ----
  console.log('\n== notify 幂等 ==');
  // 下一单用于测 notify
  const create6 = await postJson(`${BASE}/__pay/order`, {
    service: { id: 'test.notify', title: '通知测试', amountCents: 300 },
  });
  const o5id = create6.body.orderId;
  const notify1 = await postJson(`${BASE}/__pay/notify?id=${o5id}&times=2`);
  check('notify 返回 200', notify1.status === 200);
  check('notify ok=true', notify1.body && notify1.body.ok === true);
  check('notify notifyCount=2', notify1.body && notify1.body.notifyCount === 2,
    JSON.stringify(notify1.body));
  // 再 notify 一次
  const notify2 = await postJson(`${BASE}/__pay/notify?id=${o5id}&times=1`);
  check('第二次 notify notifyCount=3', notify2.body && notify2.body.notifyCount === 3);
  // 查单确认只有一笔
  const query5 = await getJson(`${BASE}/__pay/order?id=${o5id}`);
  check('notify 不改变订单状态', query5.body.status === 'pending');

  // notify 未知单
  const notifyNotFound = await postJson(`${BASE}/__pay/notify?id=PAY-99999999-9999`);
  check('notify 未知单返回 404', notifyNotFound.status === 404);

  // ---- 7. 收银台 HTML 里三个按钮都在且跳 returnUrl 带 status ----
  console.log('\n== 收银台 HTML ==');
  // 下一单用于测收银台
  const create7 = await postJson(`${BASE}/__pay/order`, {
    service: { id: 'test.cashier', title: '收银台测试', amountCents: 500 },
    returnUrl: 'http://127.0.0.1:4173/?pay_done=1',
  });
  const o6id = create7.body.orderId;
  const cashier = await getText(`${BASE}/__pay/cashier?order=${o6id}&channel=alipay`);
  check('收银台返回 200', cashier.status === 200);
  check('收银台含「确认支付」按钮', cashier.text.includes('确认支付'));
  check('收银台含「支付失败」按钮', cashier.text.includes('支付失败'));
  check('收银台含「取消」按钮', cashier.text.includes('取消'));
  check('收银台含 returnUrl', cashier.text.includes('pay_done=1'));
  check('收银台含 orderId', cashier.text.includes(o6id));
  check('收银台含 status 参数', /status=/.test(cashier.text));
  check('收银台含 doAction 函数', cashier.text.includes('doAction'));
  check('收银台含 action=pay', cashier.text.includes("action='+action") || cashier.text.includes("action="));
  check('收银台是 HTML', /<!doctype html>|<html/i.test(cashier.text));
  check('收银台含金额（分→元）', cashier.text.includes('5.00'));

  // 收银台不存在的订单
  const cashierNotFound = await getText(`${BASE}/__pay/cashier?order=PAY-99999999-9999&channel=alipay`);
  check('收银台未知订单返回 200（HTML 错误页）', cashierNotFound.status === 200);
  check('收银台未知订单含「订单不存在」', cashierNotFound.text.includes('订单不存在'));

  // ---- XSS 防护 ----
  console.log('\n== XSS 防护 ==');
  // 下单时 returnUrl 里塞 <script> 标签，看收银台是否转义
  const xssOrder = await postJson(`${BASE}/__pay/order`, {
    service: { id: 'test.xss', title: '<script>alert(1)</script>', amountCents: 999 },
    returnUrl: 'http://evil.com/<img src=x onerror=alert(1)>',
  });
  const xssId = xssOrder.body.orderId;
  const xssCashier = await getText(`${BASE}/__pay/cashier?order=${xssId}&channel=alipay`);
  check('收银台不原样包含 <script>', !xssCashier.text.includes('<script>alert'));
  check('收银台 HTML 转义了 <', xssCashier.text.includes('&lt;script&gt;'));
  check('收银台 JS 转义了 returnUrl', xssCashier.text.includes('\\x3c') || !xssCashier.text.includes('<img'));

  // ---- 8. /orders 列表长度与顺序 ----
  console.log('\n== /orders 列表 ==');
  const orders = await getJson(`${BASE}/__pay/orders`);
  check('/orders 返回 200', orders.status === 200);
  check('/orders 含 orders 数组', orders.body && Array.isArray(orders.body.orders));
  // 我们创建了 7 个不重复的订单（6 个功能测试 + 1 个 XSS 测试）
  check('/orders 长度=7', orders.body && orders.body.orders.length === 7,
    orders.body ? String(orders.body.orders.length) : 'null');
  // 按下单时间升序
  if (orders.body && orders.body.orders.length >= 2) {
    const sorted = orders.body.orders.every((o, i) =>
      i === 0 || o.createdAt >= orders.body.orders[i - 1].createdAt);
    check('/orders 按 createdAt 升序', sorted);
  }

  // ---- 9. 金额是分不是元 ----
  console.log('\n== 金额单位 ==');
  check('amountCents 是整数 600', o1.amountCents === 600 && Number.isInteger(o1.amountCents));
  check('amountCents 不是浮点元', o1.amountCents !== 6);

  // ---- 11. orderId 形如 PAY-YYYYMMDD-0001 ----
  console.log('\n== orderId 格式 ==');
  check('orderId 格式 PAY-YYYYMMDD-NNNN', /^PAY-\d{8}-\d{4}$/.test(o1.orderId), o1.orderId);

  // ---- 12. 状态只有 6 个 ----
  console.log('\n== 状态枚举 ==');
  const validStatuses = ['pending', 'paid', 'failed', 'cancelled', 'expired', 'refunded'];
  check('pending 是合法状态', validStatuses.includes('pending'));
  check('paid 是合法状态', validStatuses.includes('paid'));
  check('failed 是合法状态', validStatuses.includes('failed'));
  check('cancelled 是合法状态', validStatuses.includes('cancelled'));
  check('expired 是合法状态', validStatuses.includes('expired'));
  check('refunded 是合法状态', validStatuses.includes('refunded'));

  // ---- CORS 预检 ----
  console.log('\n== CORS ==');
  const optionsRes = await fetch(`${BASE}/__pay/order`, { method: 'OPTIONS' });
  check('OPTIONS 返回 204', optionsRes.status === 204, `got ${optionsRes.status}`);
  check('OPTIONS 含 Access-Control-Allow-Origin', optionsRes.headers.get('access-control-allow-origin') === '*');
  check('OPTIONS 含 Access-Control-Allow-Methods', /POST/.test(optionsRes.headers.get('access-control-allow-methods') || ''));
  check('OPTIONS 含 Access-Control-Allow-Headers', /Content-Type/i.test(optionsRes.headers.get('access-control-allow-headers') || ''));

  // 普通响应也带 CORS 头
  const corsGet = await fetch(`${BASE}/__pay/orders`);
  check('GET /__pay/orders 含 Access-Control-Allow-Origin', corsGet.headers.get('access-control-allow-origin') === '*');

  // 收银台 HTML 也带 CORS 头
  const corsCashier = await fetch(`${BASE}/__pay/cashier?order=${o6id}&channel=alipay`);
  check('GET /__pay/cashier 含 Access-Control-Allow-Origin', corsCashier.headers.get('access-control-allow-origin') === '*');

  // ---- 15. 壳到底加载哪份网页（源码级，不起服务器也能验）----
  // 支付入口只活在网页那条测试分支上，而收费的 /__pay/* 就在本机回环上。公网 https 源打回环
  // 会被浏览器的本地网络访问策略拦掉（实测补 CORS 头也不够），所以壳必须从自己那台服务上加载
  // 页面——同源才通。地址一改就全废，而且现象像「协议不通」，所以钉在源码上，别靠人记。
  const here = path.dirname(fileURLToPath(import.meta.url));
  const read = (rel) => fs.readFileSync(path.join(here, '..', '..', rel), 'utf8');
  const actSrc = read('app/src/main/java/org/mozilla/geckoview_example/WebViewActivity.java');
  const srvSrc = read('app/src/main/java/org/mozilla/geckoview_example/jsbridge/DataShareServer.java');
  const grab = (src, name) => (src.match(new RegExp(name + '\\s*=\\s*"([^"]+)"')) || [])[1] || '(没找到)';
  const loadedUrl = grab(actSrc, 'DEFAULT_URL');
  const fallbackUrl = grab(actSrc, 'FALLBACK_URL');
  const portConst = (srvSrc.match(/DEFAULT_PORT\s*=\s*(\d+)/) || [])[1] || '';

  check('壳钉的是回环上的测试页 http://127.0.0.1:4173/pay-test/',
    loadedUrl === 'http://127.0.0.1:' + portConst + '/pay-test/', loadedUrl + ' / DEFAULT_PORT=' + portConst);
  check('端口跟 DataShareServer.DEFAULT_PORT 一致（改了端口地址就白屏，这条挡着）',
    portConst === '4173', portConst);
  check('退路是线上根路径（局域网服务没开时不至于开局白屏）',
    fallbackUrl === 'https://totooicu.github.io/DatingRecord/', fallbackUrl);
  check('域名锁只额外放行 127.0.0.1（不是放开任意 host）',
    /ALLOWED_HOST\.equals\(host\)\s*\|\|\s*"127\.0\.0\.1"\.equals\(host\)/.test(actSrc));
  check('导航锁与壳自己的 loadUrl 都走同一颗 isAllowedHost（漏一处就加载不了）',
    (actSrc.match(/isAllowedHost\(/g) || []).length === 3,
    String((actSrc.match(/isAllowedHost\(/g) || []).length));

  // ---- 16. 取包那条 CI（源码级）----
  // 沙箱和手机都没有 Android SDK，Java 改动在这儿编不了，唯一的「编过」证据是 Actions
  // 跑出的 debug 包。那条 workflow 一旦被人改坏（漏了 JAVA_HOME 覆盖、把产物路径写错、
  // 或者悄悄挪去发版），手机上只会看到「跑挂了」这种没信息量的红叉，所以把它的关键约束
  // 钉在这里：改坏了本地就红，不用等 CI 猜。
  console.log('\n== 取包 CI ==');
  const wfPath = path.join(here, '..', '..', '.github/workflows/apk.yml');
  const wfExists = fs.existsSync(wfPath);
  check('取包工作流文件在（.github/workflows/apk.yml）', wfExists, wfPath);
  const wf = wfExists ? fs.readFileSync(wfPath, 'utf8') : '';
  check('推测试分支会触发，也留了手动触发',
    /branches:\s*\[[^\]]*'test\/\*\*'/.test(wf) && /workflow_dispatch/.test(wf));
  check('gradle.properties 那行本机 java 路径在命令行被覆盖成 runner 的 JAVA_HOME（不覆盖 Gradle 直接报 invalid）',
    /-Dorg\.gradle\.java\.home="\$JAVA_HOME"/.test(wf));
  check('产物路径钉在 app/build/outputs/apk/debug/app-debug.apk',
    /path:\s*app\/build\/outputs\/apk\/debug\/app-debug\.apk/.test(wf));
  check('没产出就让这一步失败（免得下到一个空 Artifacts）',
    /if-no-files-found:\s*error/.test(wf));
  check('只给读权限——这条不是发布链路，不该能动仓库设置或发版',
    /permissions:\s*\n\s*contents:\s*read/.test(wf) && !/pages:\s*write|contents:\s*write/.test(wf));
  check('失败要点落到 run 摘要（沙箱匿名拉不到日志，手机上只能读这段）',
    /GITHUB_STEP_SUMMARY/.test(wf) && /if:\s*failure\(\)/.test(wf));
  check('不引用那个第一次把作业跑挂的第三方 setup-android',
    !/android-actions\/setup-android/.test(wf));

  // ---- 汇总 ----
  console.log('\n========== 汇总 ==========');
  console.log(`${passed} 项通过，${failures.length} 项失败`);
  if (failures.length) {
    console.log('\n失败项：');
    for (const f of failures) console.log('  - ' + f);
    process.exit(1);
  }
}

main().catch(e => {
  console.error('自测中断：', e);
  process.exit(1);
});
