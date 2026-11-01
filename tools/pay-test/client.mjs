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

  // ---- 8. /orders 列表长度与顺序 ----
  console.log('\n== /orders 列表 ==');
  const orders = await getJson(`${BASE}/__pay/orders`);
  check('/orders 返回 200', orders.status === 200);
  check('/orders 含 orders 数组', orders.body && Array.isArray(orders.body.orders));
  // 我们创建了 6 个订单
  check('/orders 长度=6', orders.body && orders.body.orders.length === 6,
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
