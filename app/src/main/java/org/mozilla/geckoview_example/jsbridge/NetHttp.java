package org.mozilla.geckoview_example.jsbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 给网页用的最小 HTTP/1.1 客户端（原生代理），只依赖 {@code java.*}。
 *
 * <p>为什么不用 {@code HttpURLConnection}：坚果云同步要 PROPFIND / MKCOL，
 * 而 Android 的实现只认 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，其它动词直接抛
 * {@code ProtocolException}；反射改私有 {@code method} 字段在 ART 上属于隐藏接口，
 * 随时会失效。网页侧又绕不开 CORS（坚果云不返回跨域头），所以这里自己发请求行、
 * 自己收响应：任意方法、不引第三方库，并且这份文件不含任何 Android API，
 * 可以在桌面上直接编译运行（见仓库 {@code tools/net-http-test/}）。</p>
 *
 * <p>刻意不做的事：不自动跟随重定向（WebDAV 的 PUT/PROPFIND 一旦被重定向语义就变了，
 * 3xx 连同 {@code Location} 原样交回页面决定）；不做连接复用（每个请求一条短连接，
 * 配 {@code Connection: close}，省掉连接池与并发状态）；不解 gzip（我们不请求，
 * 响应带 {@code Content-Encoding} 时原样交回，让页面知道拿到的是压缩字节）。</p>
 */
public final class NetHttp {

  /** URL 最长字节数：请求行超了直接拒，不给服务端留截断歧义。 */
  public static final int MAX_URL_BYTES = 4096;
  /** 单个响应头行上限。 */
  private static final int MAX_HEADER_LINE = 8192;
  /** 响应头行数上限。 */
  private static final int MAX_HEADER_LINES = 200;
  /** 允许的请求方法：WebDAV（RFC 4918）七个扩展动词 + 常用 HTTP 动词。 */
  private static final String[] ALLOWED_METHODS = {
    "GET", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE", "CONNECT", "PATCH",
    "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK", "POST",
  };

  private NetHttp() {}

  /** 一次请求：字段都由页面经 {@code net.request} 传来，body 已在桥外层解好 base64。 */
  public static final class Request {
    public String method = "GET";
    public String url;
    /** 请求头，键大小写不敏感；{@code Host}/{@code Content-Length}/{@code Connection}/{@code Transfer-Encoding} 由本类接管。 */
    public Map<String, String> headers = new LinkedHashMap<>();

    public byte[] body; // null = 无请求体
    public int connectTimeoutMs = 15_000;
    public int readTimeoutMs = 30_000;
    /** 响应体上限，超过即报错——防一个失控的大文件把壳撑爆。 */
    public int maxResponseBytes = 32 * 1024 * 1024;
    /** 请求体上限，由调用方（桥）设定并在进入本类前校验。 */
    public String userAgent = "DatingRecord-Shell/1.0";
  }

  /** 响应：状态码 + 头（键小写，多值以 ", " 连接）+ 原始字节。 */
  public static final class Response {
    public int status;
    public String reason = "";
    public Map<String, String> headers = new TreeMap<>();
    public byte[] body = new byte[0];
  }

  /**
   * 发一次请求并取回响应。任何网络层问题都抛 {@link IOException}，
   * 消息写成中文，桥外层直接把它交给页面当错误提示。
   */
  public static Response send(Request req) throws IOException {
    String method = checkedMethod(req.method);
    URI uri = checkedUrl(req.url);
    boolean secure = "https".equalsIgnoreCase(uri.getScheme());
    String host = uri.getHost();
    int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
    String target = requestTarget(uri);

    byte[] body = req.body == null ? null : req.body;
    boolean wantsBody = body != null || methodNeedsBody(method);

    Socket plain = new Socket();
    plain.connect(new InetSocketAddress(host, port), req.connectTimeoutMs);
    try {
      Socket socket = secure ? wrapTls(plain, host, port, req) : plain;
      try {
        socket.setSoTimeout(req.readTimeoutMs);
        OutputStream out = socket.getOutputStream();
        writeRequest(out, method, target, host, port, secure, req, body, wantsBody);
        InputStream in = socket.getInputStream();
        return readResponse(in, method, req.maxResponseBytes);
      } finally {
        socket.close();
      }
    } finally {
      plain.close();
    }
  }

  /* ---------------------------------------------------------------- 请求 */

  /**
   * TLS：用「已连好的裸 socket + host」包装，这样 SNI 和主机名校验都在——
   * 坚果云的 CDN 按 SNI 分发证书，漏了它握手就失败；
   * {@code setEndpointIdentificationAlgorithm("HTTPS")} 是防中间人的那一步，不能省。
   */
  private static Socket wrapTls(Socket plain, String host, int port, Request req) throws IOException {
    SSLSocket ssl =
        (SSLSocket)
            ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(plain, host, port, /* autoClose= */ false);
    SSLParameters params = ssl.getSSLParameters();
    params.setEndpointIdentificationAlgorithm("HTTPS");
    ssl.setSSLParameters(params);
    ssl.setSoTimeout(req.readTimeoutMs);
    ssl.startHandshake();
    return ssl;
  }

  private static void writeRequest(
      OutputStream out,
      String method,
      String target,
      String host,
      int port,
      boolean secure,
      Request req,
      byte[] body,
      boolean wantsBody)
      throws IOException {
    StringBuilder h = new StringBuilder(256);
    h.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
    boolean defaultPort = (secure && port == 443) || (!secure && port == 80);
    h.append("Host: ").append(host);
    if (!defaultPort) {
      h.append(':').append(port);
    }
    h.append("\r\n");
    h.append("Connection: close\r\n");
    if (body != null) {
      h.append("Content-Length: ").append(body.length).append("\r\n");
    } else if (wantsBody) {
      h.append("Content-Length: 0\r\n");
    }
    if (req.userAgent != null && !req.userAgent.isEmpty()) {
      h.append("User-Agent: ").append(req.userAgent).append("\r\n");
    }
    h.append("Accept: */*\r\n");
    for (Map.Entry<String, String> e : req.headers.entrySet()) {
      String key = e.getKey();
      String value = e.getValue();
      if (key == null || value == null) {
        continue;
      }
      // 这四个头归本类管，页面传了也忽略：否则请求会被自己写坏
      String lower = key.toLowerCase(Locale.US);
      if (lower.equals("host")
          || lower.equals("content-length")
          || lower.equals("connection")
          || lower.equals("transfer-encoding")) {
        continue;
      }
      if (value.isEmpty()) {
        continue;
      }
      checkedHeaderValue(key, value);
      h.append(key).append(": ").append(value).append("\r\n");
    }
    h.append("\r\n");

    byte[] head = h.toString().getBytes("US-ASCII");
    out.write(head);
    if (body != null && body.length > 0) {
      out.write(body);
    }
    out.flush();
  }

  /** PUT/PROPFIND/POST 这类动词即使没内容也要带 Content-Length: 0，否则部分服务端按 411 处理。 */
  private static boolean methodNeedsBody(String method) {
    return method.equals("PUT") || method.equals("POST") || method.equals("PROPFIND")
        || method.equals("PROPPATCH") || method.equals("PATCH") || method.equals("COPY")
        || method.equals("MOVE") || method.equals("LOCK") || method.equals("MKCOL");
  }

  /* ---------------------------------------------------------------- 响应 */

  private static Response readResponse(InputStream in, String method, int maxBody) throws IOException {
    Response res = new Response();
    String statusLine = readLine(in, MAX_HEADER_LINE);
    if (statusLine == null || statusLine.isEmpty()) {
      throw new IOException("坚果云没有返回任何内容（连接被对端关闭）");
    }
    String[] parts = statusLine.split(" ", 3);
    if (parts.length < 2 || !parts[0].toUpperCase(Locale.US).startsWith("HTTP/")) {
      throw new IOException("响应不是 HTTP 格式：" + trim(statusLine, 80));
    }
    try {
      res.status = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new IOException("响应状态码读不出来：" + trim(parts[1], 20));
    }
    res.reason = parts.length > 2 ? parts[2] : "";

    int headerLines = 0;
    while (true) {
      String line = readLine(in, MAX_HEADER_LINE);
      if (line == null) {
        break;
      }
      if (line.isEmpty()) {
        break; // 头结束
      }
      if (++headerLines > MAX_HEADER_LINES) {
        throw new IOException("响应头过多（超过 " + MAX_HEADER_LINES + " 行）");
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue; // 畸形头行直接丢，别让它影响解析
      }
      String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
      String value = line.substring(colon + 1).trim();
      String prev = res.headers.get(key);
      res.headers.put(key, prev == null ? value : prev + ", " + value);
    }

    // HEAD 与 1xx/204/304 按规范没有响应体，读多了一个字节都会把下一次解析带歪
    if (method.equals("HEAD") || res.status == 204 || res.status == 304 || res.status / 100 == 1) {
      return res;
    }

    String transfer = header(res, "transfer-encoding");
    if (transfer != null && transfer.toLowerCase(Locale.US).contains("chunked")) {
      res.body = readChunked(in, maxBody);
    } else {
      int declared = intHeader(res, "content-length", -1);
      res.body = declared >= 0 ? readExactly(in, declared, maxBody) : readToEof(in, maxBody);
    }
    return res;
  }

  private static byte[] readChunked(InputStream in, int maxBody) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream(16 * 1024);
    while (true) {
      String line = readLine(in, MAX_HEADER_LINE);
      if (line == null) {
        throw new IOException("分块响应读到一半就断了");
      }
      int semi = line.indexOf(';'); // 块扩展（chunk-ext）直接忽略
      String hex = (semi >= 0 ? line.substring(0, semi) : line).trim();
      int size;
      try {
        size = Integer.parseInt(hex, 16);
      } catch (NumberFormatException e) {
        throw new IOException("分块长度读不出来：" + trim(hex, 20));
      }
      if (size == 0) {
        // 结尾：吃掉 trailer 头（一般没有）和最后一个空行
        while (true) {
          String tail = readLine(in, MAX_HEADER_LINE);
          if (tail == null || tail.isEmpty()) {
            break;
          }
        }
        break;
      }
      if (buf.size() + size > maxBody) {
        throw new IOException("响应体超过上限 " + (maxBody / 1024 / 1024) + " MB");
      }
      readInto(in, buf, size);
      String crlf = readLine(in, 8);
      if (crlf != null && !crlf.isEmpty()) {
        throw new IOException("分块结尾不符合 HTTP 格式");
      }
    }
    return buf.toByteArray();
  }

  private static byte[] readExactly(InputStream in, int count, int maxBody) throws IOException {
    if (count > maxBody) {
      throw new IOException("响应体超过上限 " + (maxBody / 1024 / 1024) + " MB");
    }
    ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(count, 64 * 1024));
    readInto(in, buf, count);
    return buf.toByteArray();
  }

  /** 没有 Content-Length：读到对端关闭（我们发的是 Connection: close，服务端会关）。 */
  private static byte[] readToEof(InputStream in, int maxBody) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream(16 * 1024);
    byte[] chunk = new byte[16 * 1024];
    while (true) {
      int n = in.read(chunk);
      if (n < 0) {
        break;
      }
      if (buf.size() + n > maxBody) {
        throw new IOException("响应体超过上限 " + (maxBody / 1024 / 1024) + " MB");
      }
      buf.write(chunk, 0, n);
    }
    return buf.toByteArray();
  }

  private static void readInto(InputStream in, ByteArrayOutputStream buf, int count) throws IOException {
    byte[] chunk = new byte[Math.min(count, 32 * 1024)];
    int remaining = count;
    while (remaining > 0) {
      int n = in.read(chunk, 0, Math.min(chunk.length, remaining));
      if (n < 0) {
        throw new IOException("响应体比声明的长度短（少了 " + remaining + " 字节）");
      }
      buf.write(chunk, 0, n);
      remaining -= n;
    }
  }

  /** 读一行（以 \n 结束，去掉尾部 \r）；对端关闭且没有内容时返回 null。 */
  private static String readLine(InputStream in, int maxLen) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
    int b;
    int len = 0;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        break;
      }
      if (b == '\r') {
        continue;
      }
      if (++len > maxLen) {
        throw new IOException("响应头单行超过 " + maxLen + " 字节");
      }
      buf.write(b);
    }
    if (b == -1 && buf.size() == 0) {
      return null;
    }
    return new String(buf.toByteArray(), "ISO-8859-1");
  }

  private static String header(Response res, String key) {
    return res.headers.get(key);
  }

  private static int intHeader(Response res, String key, int fallback) {
    String v = res.headers.get(key);
    if (v == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /* ---------------------------------------------------------------- 校验 */

  private static String checkedMethod(String method) throws IOException {
    if (method == null) {
      throw new IOException("缺少 HTTP 方法");
    }
    String upper = method.trim().toUpperCase(Locale.US);
    for (String allowed : ALLOWED_METHODS) {
      if (allowed.equals(upper)) {
        return upper;
      }
    }
    throw new IOException("不支持的 HTTP 方法：" + trim(method, 20));
  }

  private static URI checkedUrl(String url) throws IOException {
    if (url == null || url.trim().isEmpty()) {
      throw new IOException("缺少请求地址");
    }
    if (url.getBytes("UTF-8").length > MAX_URL_BYTES) {
      throw new IOException("请求地址过长（上限 " + MAX_URL_BYTES + " 字节）");
    }
    URI uri;
    try {
      uri = new URI(url.trim());
    } catch (Exception e) {
      throw new IOException("请求地址不合法：" + trim(url, 80));
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
    // 只放行 http/https：file:// 与 content:// 会把 App 私有目录暴露给网页
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new IOException("只允许 http/https 地址，收到的是：" + trim(scheme, 20));
    }
    if (uri.getHost() == null || uri.getHost().isEmpty()) {
      throw new IOException("请求地址里没有主机名");
    }
    // userinfo 会被塞进 Host 头或日志，一律拒绝：认证只走 Authorization 头
    if (uri.getUserInfo() != null) {
      throw new IOException("请求地址里不要带用户名密码，认证请用 Authorization 头");
    }
    if (uri.getRawPath() != null && hasCrLf(uri.getRawPath())) {
      throw new IOException("请求地址里有换行");
    }
    return uri;
  }

  private static String requestTarget(URI uri) {
    String path = uri.getRawPath();
    if (path == null || path.isEmpty()) {
      path = "/";
    }
    String query = uri.getRawQuery();
    return query == null || query.isEmpty() ? path : path + "?" + query;
  }

  private static void checkedHeaderValue(String key, String value) throws IOException {
    if (hasCrLf(key) || hasCrLf(value)) {
      throw new IOException("请求头「" + trim(key, 30) + "」里有换行，已拒绝");
    }
  }

  private static boolean hasCrLf(String s) {
    return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
  }

  private static String trim(String s, int max) {
    if (s == null) {
      return "";
    }
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }

  /** 供桥外层判断「这算不算超时」，避免每个调用点比字符串。 */
  public static boolean isTimeout(IOException e) {
    return e instanceof InterruptedIOException || e instanceof java.net.SocketTimeoutException;
  }

  /** 握手类错误单独提示：用户能看懂「证书/网络」比一句 SSLException 有用。 */
  public static boolean isTlsFailure(IOException e) {
    return e instanceof SSLException;
  }
}
