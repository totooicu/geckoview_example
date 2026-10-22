package org.mozilla.geckoview_example.jsbridge;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Minimal single-file HTTP server bound to 127.0.0.1. Serves {@code /} and
 * {@code /test.html} from the app assets. Used to load the test page over http
 * (loopback is a secure context, and http/https is reliably matched by the
 * bridge extension's content scripts - unlike file:///android_asset/).
 */
public class LocalPageServer {
  private static final String LOGTAG = "LocalPageServer";

  private final Context mContext;
  private ServerSocket mServer;
  private int mPort = -1;
  private volatile boolean mRunning = false;

  private LocalPageServer(Context context) {
    mContext = context.getApplicationContext();
  }

  public static LocalPageServer start(Context context) {
    LocalPageServer server = new LocalPageServer(context);
    server.startInternal();
    return server;
  }

  public int getPort() {
    return mPort;
  }

  public String getBaseUrl() {
    return "http://127.0.0.1:" + mPort + "/";
  }

  public void stop() {
    mRunning = false;
    try {
      if (mServer != null) {
        mServer.close();
      }
    } catch (IOException ignored) {
    }
  }

  private void startInternal() {
    try {
      mServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
      mPort = mServer.getLocalPort();
    } catch (IOException e) {
      Log.e(LOGTAG, "bind failed", e);
      return;
    }
    mRunning = true;
    Thread t = new Thread(this::serveLoop, "LocalPageServer");
    t.setDaemon(true);
    t.start();
    Log.i(LOGTAG, "serving on " + getBaseUrl());
  }

  private void serveLoop() {
    while (mRunning) {
      try {
        Socket socket = mServer.accept();
        handle(socket);
      } catch (IOException e) {
        if (!mRunning) {
          break;
        }
      }
    }
  }

  private void handle(Socket socket) {
    try {
      BufferedReader reader =
          new BufferedReader(new InputStreamReader(socket.getInputStream()));
      String requestLine = reader.readLine();
      if (requestLine == null || requestLine.isEmpty()) {
        socket.close();
        return;
      }
      String[] parts = requestLine.split(" ");
      String path = parts.length > 1 ? parts[1] : "/";
      // Drain request headers.
      String header;
      while ((header = reader.readLine()) != null && !header.isEmpty()) {
        // ignore
      }

      byte[] body;
      String contentType = "text/html; charset=utf-8";
      if ("/".equals(path) || "/test.html".equals(path)) {
        body = readAsset("test.html");
      } else {
        body = "404 Not Found".getBytes("UTF-8");
      }

      OutputStream out = socket.getOutputStream();
      String head =
          "HTTP/1.1 200 OK\r\n"
              + "Content-Type: "
              + contentType
              + "\r\n"
              + "Content-Length: "
              + body.length
              + "\r\n"
              + "Connection: close\r\n"
              + "\r\n";
      out.write(head.getBytes("UTF-8"));
      out.write(body);
      out.flush();
      socket.close();
    } catch (IOException e) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  private byte[] readAsset(String name) throws IOException {
    AssetManager am = mContext.getAssets();
    try (InputStream in = am.open(name)) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] tmp = new byte[8192];
      int n;
      while ((n = in.read(tmp)) > 0) {
        buf.write(tmp, 0, n);
      }
      return buf.toByteArray();
    }
  }
}
