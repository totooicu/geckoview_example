#!/usr/bin/env node
// 假坚果云：只用来验 NetHttp.java 的请求拼装与响应解析，不落盘、不联网。
//
// 覆盖：MKCOL 201/405、PROPFIND 207（Node 自动 chunked，专测分块解析）、
// PUT 字节回读（测 Content-Length 写体 + 定长读）、404、HEAD 有声明长度但无实体、
// 无 Content-Length 的连接关闭式响应、超长响应、鉴权头透传、非默认端口的 Host 头、
// 中文路径的百分号编码原样送达。
//
// 用法：node server.mjs [port]（默认 8078）；Java 侧见同目录 NetHttpSelfTest.java，
// 页面侧假壳见 ../nutstore-page-test/run.mjs（它借这个服务端，所以这里也回跨域头）。
import http from 'node:http';
import crypto from 'node:crypto';

const PORT = Number(process.argv[2] || 8078);
const USER = 'tester@example.com';
const PASS = 'app-pass-123';
const store = new Map(); // WebDAV 路径 → Buffer
const log = []; // 请求序列（method + path），页面侧用它核对「有没有真发 PROPFIND」

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');
const EXPECTED_AUTH = `Basic ${b64(`${USER}:${PASS}`)}`;

// 跨域头：Java 侧看不见（NetHttp 不校验它们），但 nutstore-page-test 里的假壳
// 跑在浏览器里，没有这几行就全被 CORS 拦死。真实坚果云恰恰不给这些头——
// 那是壳要绕开的问题，不是这个假服务端要复现的问题。
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,HEAD,PUT,POST,DELETE,PROPFIND,MKCOL,COPY,MOVE,OPTIONS',
  'access-control-allow-headers': 'Authorization,Content-Type,Depth,Overwrite,Destination',
  'access-control-max-age': '600',
};
const withCors = (h = {}) => ({ ...CORS, ...h });

function authed(req) {
  return req.headers.authorization === EXPECTED_AUTH;
}

function readBody(req) {
  return new Promise((resolve) => {
    const parts = [];
    req.on('data', (c) => parts.push(c));
    req.on('end', () => resolve(Buffer.concat(parts)));
  });
}

let last = {};
const server = http.createServer(async (req, res) => {
  const path = req.url;
  const body = await readBody(req);

  // 预检：页面侧假壳用浏览器 fetch 代发，非简单方法（PROPFIND/MKCOL…）会先发
  // OPTIONS。这条不能进 last，否则 Java 侧回读到的「上一条」永远是预检。
  if (req.method === 'OPTIONS') {
    res.writeHead(204, withCors());
    return res.end();
  }

  // 每次请求都记下来，Java 侧用 /__last 回查（Host 头、原始路径、方法名）。
  // 回查自身的几条不能覆盖记录，否则读到的永远是「刚刚那次 GET」。
  if (!path.startsWith('/__')) {
    last = {
      method: req.method,
      url: path,
      host: req.headers.host,
      auth: req.headers.authorization ?? '',
      depth: req.headers.depth ?? '',
      rawBodyLen: body.length,
      ct: req.headers['content-type'] ?? '',
    };
    log.push(req.method + ' ' + path);
  }

  if (path === '/__last') {
    res.writeHead(200, withCors({ 'content-type': 'application/json' }));
    return res.end(JSON.stringify(last));
  }
  if (path === '/__config') {
    res.writeHead(200, withCors({ 'content-type': 'application/json' }));
    return res.end(JSON.stringify({ user: USER, pass: PASS, expectedAuth: EXPECTED_AUTH }));
  }
  if (path === '/__log') {
    res.writeHead(200, withCors({ 'content-type': 'application/json' }));
    return res.end(JSON.stringify(log));
  }
  if (path === '/__files') {
    res.writeHead(200, withCors({ 'content-type': 'application/json' }));
    return res.end(JSON.stringify([...store.keys()]));
  }
  if (path === '/__reset') {
    store.clear();
    log.length = 0;
    res.writeHead(204, withCors());
    return res.end();
  }

  // 未授权：除 __ 前缀外一律先要 Basic 头（坚果云就是这行为）
  if (!path.startsWith('/__') && !authed(req)) {
    res.writeHead(401, withCors({ 'www-authenticate': 'Basic realm="Nutstore"', 'content-type': 'text/plain' }));
    return res.end('需要应用密码');
  }

  if (path === '/__giant') {
    // 声明一个超过客户端上限的响应体，测「响应体超过上限」这条错误路径
    res.writeHead(200, withCors({ 'content-length': 5 * 1024 * 1024, 'content-type': 'application/octet-stream' }));
    res.write(Buffer.alloc(1024 * 1024));
    res.write(Buffer.alloc(4 * 1024 * 1024));
    return res.end();
  }

  if (path === '/__no-length') {
    // 手写一条不带 Content-Length 的响应，靠 Connection: close 收尾（HTTP/1.0 式读法）
    // 这条本来就是给 Java 侧测裸读法的，浏览器侧不跑它，所以不塞跨域头。
    res.socket.write('HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: text/plain\r\n\r\n读到连接关闭为止');
    return res.socket.end();
  }

  if (req.method === 'MKCOL') {
    if (store.has(path)) {
      res.writeHead(405, withCors({ 'content-type': 'text/plain' }));
      return res.end('Method Not Allowed');
    }
    store.set(path, null); // 目录
    res.writeHead(201, withCors());
    return res.end();
  }

  if (req.method === 'PROPFIND') {
    const children = [...store.keys()].filter((k) => k !== path && k.startsWith(path));
    // 不写 content-length，Node 自动分块传输——正好测 chunked 解析
    res.writeHead(207, withCors({ 'content-type': 'application/xml;charset=utf-8' }));
    let xml = '<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">';
    xml += `<d:response><d:href>${path}</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>`;
    for (const c of children) {
      const size = store.get(c) ? store.get(c).length : 0;
      xml += `<d:response><d:href>${c}</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>${size}</d:getcontentlength></d:prop></d:propstat></d:response>`;
    }
    xml += '</d:multistatus>';
    return res.end(xml);
  }

  if (req.method === 'PUT') {
    store.set(path, body);
    res.writeHead(201, withCors({ etag: `"${crypto.createHash('sha256').update(body).digest('hex').slice(0, 16)}"` }));
    return res.end();
  }

  if (req.method === 'GET') {
    if (!store.has(path) || store.get(path) === null) {
      res.writeHead(404, withCors({ 'content-type': 'text/plain' }));
      return res.end('找不到该文件');
    }
    const buf = store.get(path);
    res.writeHead(200, withCors({ 'content-length': buf.length, 'content-type': 'application/octet-stream' }));
    return res.end(buf);
  }

  if (req.method === 'HEAD') {
    // 声明长度但不给实体：客户端要是照 Content-Length 读就会卡住
    res.writeHead(200, withCors({ 'content-length': 4096 }));
    return res.end();
  }

  if (req.method === 'DELETE') {
    if (!store.has(path)) {
      res.writeHead(404, withCors());
      return res.end();
    }
    store.delete(path);
    res.writeHead(204, withCors());
    return res.end();
  }

  res.writeHead(400, withCors({ 'content-type': 'text/plain' }));
  return res.end(`假坚果云不认这个方法：${req.method}`);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`fake-nutstore listening on http://127.0.0.1:${PORT}`);
});
