package org.mozilla.geckoview_example.jsbridge;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 壳端「跨设备操作」服务器。
 *
 * <p>浏览器页面不能监听端口，所以由壳在本机起一个 HTTP + WebSocket 服务：</p>
 *
 * <ol>
 *   <li>把网页原样代理到 {@code http://手机IP:端口}，客户端设备打开就能加载同一份页面；</li>
 *   <li>在 {@code /remote?token=} 上做 WebSocket 握手，按 token 分房间转发；</li>
 *   <li>{@code /__remote/info}、{@code /__remote/listen} 与
 *       {@code tools/remote-relay/server.mjs} 保持同一套接口，
 *       网页端无需区分「Node 中继」还是「壳端服务」。</li>
 * </ol>
 *
 * <p>这个类刻意不依赖 Android API（只用 java.*）：既能编进 APK，也能在普通
 * JVM 上跑自测（见 {@code tools/datashare-test}）。壳只做转发，不碰数据库——
 * 数据始终在壳里那个网页（gh-pages 源）的 IndexedDB 中。</p>
 */
public final class DataShareServer {
  public static final int DEFAULT_PORT = 4173;

  /** 客户端加载页面时走这个源：与壳内网页同一个部署，版本不会分叉。 */
  private static final String PROXY_ROOT = "https://totooicu.github.io/DatingRecord/";

  private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  private static final int MAX_FRAME = 64 * 1024 * 1024;
  private static final int MAX_CACHE_BYTES = 32 * 1024 * 1024;
  private static final Charset UTF8 = Charset.forName("UTF-8");

  private static volatile DataShareServer sInstance;

  public static DataShareServer get() {
    if (sInstance == null) {
      synchronized (DataShareServer.class) {
        if (sInstance == null) new DataShareServer();
      }
    }
    return sInstance;
  }

  private DataShareServer() {
    sInstance = this;
  }

  private ServerSocket server;
  private Thread acceptThread;
  private ExecutorService pool;
  private volatile boolean running;
  private int port = DEFAULT_PORT;
  private volatile String token = "";
  private final Map<String, Room> rooms = new ConcurrentHashMap<>();
  private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
  private final LinkedHashMap<String, CacheEntry> cache =
      new LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
          return size() > 64;
        }
      };
  private long cacheBytes;

  // ---------------------------------------------------------------- lifecycle

  /** 绑定端口并开始监听；同端口重复调用只更新 token（有服务端在线时不许换）。 */
  public synchronized void start(int port, String token) throws IOException {
    String t = token == null ? "" : token.trim();
    if (t.isEmpty()) throw new IOException("token 不能为空");
    if (port <= 0 || port > 65535) throw new IOException("端口号要在 1–65535 之间");

    if (running) {
      if (port == this.port) {
        if (!t.equals(this.token)) {
          if (hostOnline()) throw new IOException("已有服务端在用当前端口，token 不能中途更换");
          this.token = t;
        }
        return;
      }
      stop();
    }

    ServerSocket ss = new ServerSocket();
    ss.setReuseAddress(true);
    try {
      ss.bind(new InetSocketAddress(port), 64);
    } catch (IOException e) {
      try {
        ss.close();
      } catch (IOException ignored) {
        // ignore
      }
      throw new IOException("端口 " + port + " 无法监听（可能被其他程序占用）", e);
    }

    server = ss;
    this.port = port;
    this.token = t;
    running = true;
    pool = Executors.newCachedThreadPool(
        new ThreadFactory() {
          private int seq;

          @Override
          public synchronized Thread newThread(Runnable r) {
            Thread th = new Thread(r, "data-share-" + (++seq));
            th.setDaemon(true);
            return th;
          }
        });
    acceptThread = new Thread(this::acceptLoop, "data-share-accept");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  public synchronized void stop() {
    running = false;
    ServerSocket ss = server;
    server = null;
    if (ss != null) {
      try {
        ss.close();
      } catch (IOException ignored) {
        // ignore
      }
    }
    for (Socket s : sockets) {
      try {
        s.close();
      } catch (IOException ignored) {
        // ignore
      }
    }
    sockets.clear();
    for (Room r : rooms.values()) {
      if (r.host != null) r.host.close(1001);
      for (Conn c : new ArrayList<>(r.clients.values())) c.close(1001);
    }
    rooms.clear();
    if (pool != null) {
      pool.shutdownNow();
      pool = null;
    }
  }

  public boolean isRunning() {
    return running;
  }

  public int getPort() {
    return port;
  }

  public String getToken() {
    return token;
  }

  public boolean hostOnline() {
    Room r = rooms.get(token);
    return r != null && r.host != null;
  }

  public int clientCount() {
    Room r = rooms.get(token);
    return r == null ? 0 : r.clients.size();
  }

  /** 给网页 / 通知栏看的快照。 */
  public Status status() {
    return new Status(running, port, token, hostOnline(), clientCount(), lanAddresses());
  }

  public static final class Status {
    public final boolean running;
    public final int port;
    public final String token;
    public final boolean hostOnline;
    public final int clients;
    public final List<String> addresses;

    Status(boolean running, int port, String token, boolean hostOnline, int clients, List<String> addresses) {
      this.running = running;
      this.port = port;
      this.token = token;
      this.hostOnline = hostOnline;
      this.clients = clients;
      this.addresses = addresses;
    }
  }

  // ---------------------------------------------------------------- accept loop

  private void acceptLoop() {
    while (running) {
      Socket socket;
      try {
        socket = server.accept();
      } catch (IOException e) {
        break;
      }
      sockets.add(socket);
      ExecutorService p = pool;
      if (p == null) break;
      p.execute(() -> handleConnection(socket));
    }
  }

  private void handleConnection(Socket socket) {
    try {
      socket.setTcpNoDelay(true);
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();

      String requestLine = readLine(in);
      if (requestLine == null || requestLine.isEmpty()) {
        socket.close();
        return;
      }
      String[] parts = requestLine.split(" ");
      if (parts.length < 2) {
        socket.close();
        return;
      }
      String method = parts[0];
      String target = parts[1];

      Map<String, String> headers = new HashMap<>();
      String line;
      int guard = 0;
      while ((line = readLine(in)) != null && !line.isEmpty()) {
        int at = line.indexOf(':');
        if (at > 0) headers.put(line.substring(0, at).trim().toLowerCase(Locale.ROOT), line.substring(at + 1).trim());
        if (++guard > 128) {
          socket.close();
          return;
        }
      }

      String upgrade = headers.get("upgrade");
      if (upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket")) {
        handleUpgrade(socket, in, out, target, headers);
        return;
      }
      handleHttp(socket, out, method, target);
    } catch (IOException e) {
      closeQuietly(socket);
    } finally {
      sockets.remove(socket);
    }
  }

  // ---------------------------------------------------------------- HTTP

  private void handleHttp(Socket socket, OutputStream out, String method, String target) throws IOException {
    ParsedUrl url = ParsedUrl.parse(target);

    if ("/__remote/info".equals(url.path)) {
      writeJson(out, infoJson(url.query.get("token")));
      return;
    }
    if ("/__remote/listen".equals(url.path)) {
      if (!"POST".equals(method)) {
        writeJson(out, "{\"ok\":false,\"error\":\"请用 POST\"}");
        return;
      }
      writeJson(out, "{\"ok\":false,\"error\":\"壳端服务固定使用一个端口，请用壳里的「开启」修改端口\"}");
      return;
    }
    if ("OPTIONS".equals(method)) {
      out.write(
          ("HTTP/1.1 204 No Content\r\n"
                  + "Access-Control-Allow-Origin: *\r\n"
                  + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                  + "Access-Control-Allow-Headers: Content-Type\r\n"
                  + "Content-Length: 0\r\n"
                  + "Connection: close\r\n\r\n")
              .getBytes(UTF8));
      out.flush();
      return;
    }
    if (!"GET".equals(method) && !"HEAD".equals(method)) {
      writeText(out, "405 Method Not Allowed", "text/plain; charset=utf-8", "method not allowed");
      return;
    }

    String path = url.path;
    if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
    if (path.endsWith("/")) path = path + "index.html";

    CacheEntry entry = null;
    try {
      entry = fetch(path);
    } catch (IOException e) {
      entry = cached(path);
    }
    if (entry == null) {
      writeText(
          out,
          "502 Bad Gateway",
          "text/html; charset=utf-8",
          "<!doctype html><meta charset=utf-8><title>壳端服务</title>"
              + "<body style=\"font:15px/1.6 system-ui;padding:24px\">"
              + "<h3>网页没能取到</h3><p>壳连不上 "
              + PROXY_ROOT
              + "。请确认手机能上网后再试；已经缓存过的页面仍可打开。</p></body>");
      return;
    }

    String type = entry.type;
    if (type == null || type.isEmpty()) type = mimeOf(path);
    boolean noStore = path.endsWith("/index.html") || path.endsWith("/sw.js");
    byte[] head =
        ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: "
                + type
                + "\r\n"
                + "Content-Length: "
                + entry.body.length
                + "\r\n"
                + "Cache-Control: "
                + (noStore ? "no-store" : "public, max-age=3600")
                + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n")
            .getBytes(UTF8);
    out.write(head);
    if (!"HEAD".equals(method)) out.write(entry.body);
    out.flush();
  }

  private String infoJson(String askedToken) {
    Room r = rooms.get(token);
    boolean online = r != null && r.host != null;
    int clients = r == null ? 0 : r.clients.size();
    StringBuilder b = new StringBuilder();
    b.append("{\"ok\":true,\"port\":").append(port);
    b.append(",\"hostOnline\":").append(online);
    b.append(",\"clients\":").append(clients);
    b.append(",\"tokenOk\":").append(askedToken == null ? "null" : String.valueOf(askedToken.equals(token)));
    b.append(",\"addresses\":[");
    List<String> addrs = lanAddresses();
    for (int i = 0; i < addrs.size(); i++) {
      if (i > 0) b.append(',');
      b.append(quote(addrs.get(i)));
    }
    b.append("]}");
    return b.toString();
  }

  private void writeJson(OutputStream out, String body) throws IOException {
    byte[] bytes = body.getBytes(UTF8);
    out.write(
        ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Content-Length: "
                + bytes.length
                + "\r\n"
                + "Connection: close\r\n\r\n")
            .getBytes(UTF8));
    out.write(bytes);
    out.flush();
  }

  private void writeText(OutputStream out, String status, String type, String body) throws IOException {
    byte[] bytes = body.getBytes(UTF8);
    out.write(
        ("HTTP/1.1 "
                + status
                + "\r\nContent-Type: "
                + type
                + "\r\nContent-Length: "
                + bytes.length
                + "\r\nConnection: close\r\n\r\n")
            .getBytes(UTF8));
    out.write(bytes);
    out.flush();
  }

  // ---------------------------------------------------------------- static proxy

  private static final class CacheEntry {
    final byte[] body;
    final String type;
    final long at;

    CacheEntry(byte[] body, String type, long at) {
      this.body = body;
      this.type = type;
      this.at = at;
    }
  }

  private synchronized CacheEntry cached(String path) {
    CacheEntry e = cache.get(path);
    return e;
  }

  private CacheEntry fetch(String path) throws IOException {
    CacheEntry hit = cached(path);
    boolean fresh = hit != null && System.currentTimeMillis() - hit.at < 10 * 60 * 1000;
    if (fresh && !path.endsWith("/index.html") && !path.endsWith("/sw.js")) return hit;

    String rel = path.startsWith("/") ? path.substring(1) : path;
    CacheEntry fetched = fetchFromOrigin(rel);
    synchronized (this) {
      cacheBytes -= cache.containsKey(path) ? cache.get(path).body.length : 0;
      cache.put(path, fetched);
      cacheBytes += fetched.body.length;
      while (cacheBytes > MAX_CACHE_BYTES && !cache.isEmpty()) {
        String eldest = cache.keySet().iterator().next();
        cacheBytes -= cache.remove(eldest).body.length;
      }
    }
    return fetched;
  }

  private CacheEntry fetchFromOrigin(String rel) throws IOException {
    URL url = new URL(PROXY_ROOT + rel);
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    try {
      c.setInstanceFollowRedirects(true);
      c.setConnectTimeout(8000);
      c.setReadTimeout(20000);
      c.setRequestProperty("User-Agent", "ChaohuixisiShell/1.2 (Android LAN share)");
      c.setRequestProperty("Accept", "*/*");
      int code = c.getResponseCode();
      if (code >= 400) {
        if (rel.indexOf('.') < 0 && !rel.isEmpty()) return fetchFromOrigin("");
        throw new IOException("HTTP " + code);
      }
      InputStream in = c.getInputStream();
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] chunk = new byte[16384];
      int n;
      while ((n = in.read(chunk)) > 0) {
        buf.write(chunk, 0, n);
        if (buf.size() > MAX_CACHE_BYTES) throw new IOException("页面资源过大");
      }
      in.close();
      String type = c.getContentType();
      return new CacheEntry(buf.toByteArray(), type, System.currentTimeMillis());
    } finally {
      c.disconnect();
    }
  }

  private static String mimeOf(String path) {
    String p = path.toLowerCase(Locale.ROOT);
    if (p.endsWith(".html")) return "text/html; charset=utf-8";
    if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript; charset=utf-8";
    if (p.endsWith(".css")) return "text/css; charset=utf-8";
    if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json; charset=utf-8";
    if (p.endsWith(".wasm")) return "application/wasm";
    if (p.endsWith(".png")) return "image/png";
    if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
    if (p.endsWith(".svg")) return "image/svg+xml";
    if (p.endsWith(".webp")) return "image/webp";
    if (p.endsWith(".gif")) return "image/gif";
    if (p.endsWith(".ico")) return "image/x-icon";
    if (p.endsWith(".woff2")) return "font/woff2";
    if (p.endsWith(".txt")) return "text/plain; charset=utf-8";
    return "application/octet-stream";
  }

  // ---------------------------------------------------------------- websocket

  private void handleUpgrade(
      Socket socket, InputStream in, OutputStream out, String target, Map<String, String> headers) throws IOException {
    ParsedUrl url = ParsedUrl.parse(target);
    if (!"/remote".equals(url.path) && !"/".equals(url.path)) {
      writeText(out, "404 Not Found", "text/plain; charset=utf-8", "not found");
      closeQuietly(socket);
      return;
    }
    String asked = url.query.get("token");
    if (asked == null || !asked.equals(token)) {
      writeText(out, "401 Unauthorized", "text/plain; charset=utf-8", "token 不对");
      closeQuietly(socket);
      return;
    }
    String key = headers.get("sec-websocket-key");
    if (key == null || key.isEmpty()) {
      writeText(out, "400 Bad Request", "text/plain; charset=utf-8", "missing websocket key");
      closeQuietly(socket);
      return;
    }
    String accept = base64(sha1((key + WS_GUID).getBytes(UTF8)));
    out.write(
        ("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: "
                + accept
                + "\r\n\r\n")
            .getBytes(UTF8));
    out.flush();

    Conn conn = new Conn(socket, new DataInputStream(in), out, asked);
    // 先登记再读：处理线程由外部连接线程负责
    conn.run();
  }

  private static final class Room {
    Conn host;
    final Map<String, Conn> clients = new LinkedHashMap<>();
  }

  private int seq;

  private final class Conn {
    final Socket socket;
    final DataInputStream in;
    final OutputStream out;
    final String token;
    final Object writeLock = new Object();
    volatile boolean closed;
    String id = "";
    String role = "";
    String name = "";

    Conn(Socket socket, DataInputStream in, OutputStream out, String token) {
      this.socket = socket;
      this.in = in;
      this.out = out;
      this.token = token;
    }

    void run() {
      ByteArrayOutputStream parts = new ByteArrayOutputStream();
      int msgOp = -1;
      try {
        while (!closed) {
          int b0 = in.readUnsignedByte();
          int b1 = in.readUnsignedByte();
          if ((b0 & 0x70) != 0) {
            close(1002);
            break;
          }
          boolean fin = (b0 & 0x80) != 0;
          int op = b0 & 0x0f;
          boolean masked = (b1 & 0x80) != 0;
          long len = b1 & 0x7f;
          if (len == 126) len = in.readUnsignedShort();
          else if (len == 127) len = in.readLong();
          if (len < 0 || len > MAX_FRAME) {
            close(1009);
            break;
          }
          byte[] mask = null;
          if (masked) {
            mask = new byte[4];
            in.readFully(mask);
          }
          byte[] payload = new byte[(int) len];
          in.readFully(payload);
          if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
          }

          if (op == 0x8) {
            close(1000);
            break;
          }
          if (op == 0x9) {
            sendFrame(0xA, payload);
            continue;
          }
          if (op == 0xA) continue;

          if (op == 0x1 || op == 0x2) {
            msgOp = op;
            parts.reset();
            parts.write(payload);
          } else if (op == 0x0) {
            if (msgOp < 0) {
              close(1002);
              break;
            }
            parts.write(payload);
          } else {
            close(1002);
            break;
          }
          if (!fin) continue;

          byte[] whole = parts.toByteArray();
          parts.reset();
          int finished = msgOp;
          msgOp = -1;
          if (finished == 0x1) onText(new String(whole, UTF8));
        }
      } catch (IOException e) {
        // 对端断开
      } finally {
        leave();
      }
    }

    void onText(String text) {
      String t = Json.field(text, "t");
      if ("hello".equals(t)) {
        Room room = room(token);
        role = "host".equals(Json.field(text, "role")) ? "host" : "client";
        name = Json.field(text, "name");
        if (name.isEmpty()) name = role;
        if ("host".equals(role)) {
          if (room.host != null) {
            reject("已有服务端在线，请先关闭原来的服务端页面");
            return;
          }
          id = "host";
          room.host = this;
        } else {
          synchronized (DataShareServer.this) {
            id = "c" + (++seq);
          }
          room.clients.put(id, this);
        }
        StringBuilder w = new StringBuilder();
        w.append("{\"t\":\"welcome\",\"id\":").append(quote(id));
        w.append(",\"role\":").append(quote(role));
        w.append(",\"hostOnline\":").append(room.host != null);
        w.append(",\"clients\":[");
        if ("host".equals(role)) {
          boolean first = true;
          for (Conn c : room.clients.values()) {
            if (!first) w.append(',');
            first = false;
            w.append(peerJson(c));
          }
        }
        w.append("]}");
        reply(w.toString());
        if ("client".equals(role) && room.host != null) {
          room.host.reply("{\"t\":\"peer\",\"event\":\"join\"," + peerFields(this) + "}");
        }
        return;
      }

      if (id.isEmpty()) {
        reject("请先发送 hello");
        return;
      }
      Room room = room(token);
      if ("host".equals(role)) {
        if ("to".equals(t)) {
          String to = Json.field(text, "to");
          Conn c = room.clients.get(to);
          if (c != null) c.reply("{\"t\":\"msg\",\"from\":" + quote(id) + ",\"msg\":" + Json.raw(text, "msg") + "}");
          return;
        }
        if ("broadcast".equals(t)) {
          String raw = Json.raw(text, "msg");
          for (Conn c : room.clients.values()) {
            c.reply("{\"t\":\"msg\",\"from\":" + quote(id) + ",\"msg\":" + raw + "}");
          }
          return;
        }
        return;
      }

      // 客户端：永远发给服务端
      if (room.host == null) {
        reply("{\"t\":\"error\",\"error\":\"服务端不在线\"}");
        return;
      }
      if ("to".equals(t) || "broadcast".equals(t)) {
        room.host.reply(
            "{\"t\":\"msg\",\"from\":" + quote(id) + ",\"msg\":" + Json.raw(text, "msg") + "}");
      }
    }

    void reply(String json) {
      sendFrame(0x1, json.getBytes(UTF8));
    }

    void reject(String text) {
      reply("{\"t\":\"error\",\"error\":" + quote(text) + "}");
      close(1008);
    }

    void sendFrame(int opcode, byte[] payload) {
      if (closed) return;
      int len = payload.length;
      byte[] header;
      if (len < 126) {
        header = new byte[] {(byte) (0x80 | opcode), (byte) len};
      } else if (len < 65536) {
        header =
            new byte[] {
              (byte) (0x80 | opcode), 126, (byte) ((len >>> 8) & 0xff), (byte) (len & 0xff)
            };
      } else {
        header = new byte[10];
        header[0] = (byte) (0x80 | opcode);
        header[1] = 127;
        long l = len;
        for (int i = 0; i < 8; i++) header[9 - i] = (byte) ((l >>> (8 * i)) & 0xff);
      }
      synchronized (writeLock) {
        try {
          out.write(header);
          out.write(payload);
          out.flush();
        } catch (IOException e) {
          leave();
        }
      }
    }

    void close(int code) {
      if (closed) return;
      byte[] body = new byte[] {(byte) ((code >>> 8) & 0xff), (byte) (code & 0xff)};
      sendFrame(0x8, body);
      leave();
    }

    void leave() {
      if (closed) return;
      closed = true;
      Room room = rooms.get(token);
      if (room != null) {
        if (room.host == this) {
          room.host = null;
          for (Conn c : new ArrayList<>(room.clients.values())) {
            c.reply("{\"t\":\"error\",\"error\":\"服务端已离线，请回到本地数据\"}");
            c.close(1011);
          }
          room.clients.clear();
        } else if (!id.isEmpty() && room.clients.get(id) == this) {
          room.clients.remove(id);
          if (room.host != null) {
            room.host.reply("{\"t\":\"peer\",\"event\":\"leave\"," + peerFields(this) + "}");
          }
        }
      }
      closeQuietly(socket);
    }
  }

  private Room room(String token) {
    Room r = rooms.get(token);
    if (r == null) {
      r = new Room();
      Room prev = rooms.putIfAbsent(token, r);
      if (prev != null) r = prev;
    }
    return r;
  }

  private static String peerFields(Conn c) {
    return "\"id\":" + quote(c.id) + ",\"name\":" + quote(c.name) + ",\"role\":" + quote(c.role);
  }

  private static String peerJson(Conn c) {
    return "{" + peerFields(c) + "}";
  }

  // ---------------------------------------------------------------- helpers

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') break;
      if (c != '\r') buf.write(c);
      if (buf.size() > 8192) throw new IOException("header too long");
    }
    if (c == -1 && buf.size() == 0) return null;
    return new String(buf.toByteArray(), UTF8);
  }

  private static byte[] sha1(byte[] data) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      return md.digest(data);
    } catch (Exception e) {
      throw new IOException("SHA-1 不可用", e);
    }
  }

  private static final char[] B64 =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

  private static String base64(byte[] data) {
    StringBuilder b = new StringBuilder((data.length + 2) / 3 * 4);
    int i = 0;
    while (i + 2 < data.length) {
      int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
      b.append(B64[(n >>> 18) & 63]).append(B64[(n >>> 12) & 63]).append(B64[(n >>> 6) & 63]).append(B64[n & 63]);
      i += 3;
    }
    if (i < data.length) {
      int n = (data[i] & 0xff) << 16;
      if (i + 1 < data.length) n |= (data[i + 1] & 0xff) << 8;
      b.append(B64[(n >>> 18) & 63]).append(B64[(n >>> 12) & 63]);
      b.append(i + 1 < data.length ? B64[(n >>> 6) & 63] : '=');
      b.append('=');
    }
    return b.toString();
  }

  static String quote(String s) {
    if (s == null) return "null";
    StringBuilder b = new StringBuilder(s.length() + 2);
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        case '\n':
          b.append("\\n");
          break;
        case '\r':
          b.append("\\r");
          break;
        case '\t':
          b.append("\\t");
          break;
        default:
          if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else b.append(c);
      }
    }
    b.append('"');
    return b.toString();
  }

  public static List<String> lanAddresses() {
    List<String> wifi = new ArrayList<>();
    List<String> others = new ArrayList<>();
    try {
      Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
      while (ifs != null && ifs.hasMoreElements()) {
        NetworkInterface ni = ifs.nextElement();
        if (!ni.isUp() || ni.isLoopback()) continue;
        String name = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.ROOT);
        boolean preferred = name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth");
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
          InetAddress a = ia.getAddress();
          if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
          if (!a.isSiteLocalAddress()) continue;
          (preferred ? wifi : others).add(a.getHostAddress());
        }
      }
    } catch (Exception ignored) {
      // 权限或系统限制：返回空列表，网页会退回让用户手填
    }
    wifi.addAll(others);
    return wifi;
  }

  private static void closeQuietly(Socket s) {
    try {
      s.close();
    } catch (IOException ignored) {
      // ignore
    }
  }

  private static final class ParsedUrl {
    final String path;
    final Map<String, String> query;

    ParsedUrl(String path, Map<String, String> query) {
      this.path = path;
      this.query = query;
    }

    static ParsedUrl parse(String target) {
      String raw = target == null ? "/" : target;
      int q = raw.indexOf('?');
      String p = q >= 0 ? raw.substring(0, q) : raw;
      Map<String, String> query = new HashMap<>();
      if (q >= 0) {
        for (String pair : raw.substring(q + 1).split("&")) {
          if (pair.isEmpty()) continue;
          int eq = pair.indexOf('=');
          String k = eq >= 0 ? pair.substring(0, eq) : pair;
          String v = eq >= 0 ? pair.substring(eq + 1) : "";
          try {
            query.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
          } catch (Exception ignored) {
            query.put(k, v);
          }
        }
      }
      try {
        p = URLDecoder.decode(p, "UTF-8");
      } catch (Exception ignored) {
        // 保留原样
      }
      return new ParsedUrl(p, query);
    }
  }

  /** 只认我们自己的协议：取顶层字符串 / 原始子对象，够用且不引第三方 JSON。 */
  private static final class Json {
    static String field(String text, String key) {
      int at = indexOfKey(text, key);
      if (at < 0) return "";
      int i = at;
      while (i < text.length() && text.charAt(i) != ':') i++;
      if (i >= text.length()) return "";
      i++;
      while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
      if (i >= text.length() || text.charAt(i) != '"') return "";
      return readString(text, i + 1);
    }

    static String raw(String text, String key) {
      int at = indexOfKey(text, key);
      if (at < 0) return "null";
      int i = at;
      while (i < text.length() && text.charAt(i) != ':') i++;
      if (i >= text.length()) return "null";
      i++;
      while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
      if (i >= text.length()) return "null";
      char c = text.charAt(i);
      if (c == '{' || c == '[') {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int j = i; j < text.length(); j++) {
          char x = text.charAt(j);
          if (inStr) {
            if (esc) esc = false;
            else if (x == '\\') esc = true;
            else if (x == '"') inStr = false;
            continue;
          }
          if (x == '"') inStr = true;
          else if (x == '{' || x == '[') depth++;
          else if (x == '}' || x == ']') {
            depth--;
            if (depth == 0) return text.substring(i, j + 1);
          }
        }
        return "null";
      }
      if (c == '"') {
        int end = stringEnd(text, i + 1);
        return text.substring(i, end);
      }
      int end = i;
      while (end < text.length() && ",}] \n\r\t".indexOf(text.charAt(end)) < 0) end++;
      return text.substring(i, end);
    }

    private static int indexOfKey(String text, String key) {
      String needle = "\"" + key + "\":";
      int at = text.indexOf(needle);
      if (at >= 0) return at;
      needle = "\"" + key + "\" :";
      return text.indexOf(needle);
    }

    private static String readString(String text, int start) {
      int end = stringEnd(text, start);
      String raw = text.substring(start, Math.max(start, end - 1));
      return unescape(raw);
    }

    private static int stringEnd(String text, int start) {
      boolean esc = false;
      for (int i = start; i < text.length(); i++) {
        char c = text.charAt(i);
        if (esc) {
          esc = false;
          continue;
        }
        if (c == '\\') esc = true;
        else if (c == '"') return i + 1;
      }
      return text.length();
    }

    private static String unescape(String s) {
      if (s.indexOf('\\') < 0) return s;
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c != '\\' || i + 1 >= s.length()) {
          b.append(c);
          continue;
        }
        char n = s.charAt(++i);
        switch (n) {
          case 'n':
            b.append('\n');
            break;
          case 'r':
            b.append('\r');
            break;
          case 't':
            b.append('\t');
            break;
          case 'b':
            b.append('\b');
            break;
          case 'f':
            b.append('\f');
            break;
          case 'u':
            if (i + 4 < s.length()) {
              try {
                b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                i += 4;
              } catch (NumberFormatException e) {
                b.append(n);
              }
            }
            break;
          default:
            b.append(n);
        }
      }
      return b.toString();
    }
  }
}
