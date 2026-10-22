// Bridge background script.
// Owns the native messaging port (connectNative) and routes messages between
// page content scripts (runtime.onConnect ports) and the native app.
(function () {
  "use strict";

  function log(msg) {
    try { console.log("[gv-bridge-bg] " + msg); } catch (e) {}
  }

  log("background started");

  var nativePort = null;
  var pagePorts = new Set();
  var nativeAttempts = 0;
  var nativeLastError = "(not attempted)";

  function broadcastHeartbeat() {
    for (var p of pagePorts) {
      try {
        p.postMessage({
          event: "bg-heartbeat",
          data: {
            nativeConnected: !!nativePort,
            nativeAttempts: nativeAttempts,
            nativeLastError: nativeLastError,
            pagePorts: pagePorts.size
          }
        });
      } catch (e) {}
    }
  }

  function connectNative() {
    nativeAttempts++;
    nativeLastError = "(connecting...)";
    broadcastHeartbeat();
    log("connectNative attempt " + nativeAttempts);
    try {
      nativePort = browser.runtime.connectNative("bridge");
    } catch (e) {
      nativeLastError = "THREW: " + e;
      log("connectNative THREW: " + e);
      broadcastHeartbeat();
      setTimeout(connectNative, 1000);
      return;
    }
    if (!nativePort) {
      nativeLastError = "returned null";
      broadcastHeartbeat();
      setTimeout(connectNative, 1000);
      return;
    }
    nativeLastError = "(connected, waiting for messages)";
    broadcastHeartbeat();
    log("nativePort created");

    nativePort.onMessage.addListener(function (msg) {
      log("native -> pages: " + JSON.stringify(msg).substring(0, 200));
      for (var p of pagePorts) {
        try { p.postMessage(msg); } catch (e) {}
      }
    });
    nativePort.onDisconnect.addListener(function () {
      var err = browser.runtime.lastError;
      nativeLastError = "disconnected: " + (err ? err.message : "no lastError");
      log("nativePort disconnected. lastError=" + (err ? err.message : "none"));
      nativePort = null;
      broadcastHeartbeat();
      setTimeout(connectNative, 1000);
    });
  }

  browser.runtime.onConnect.addListener(function (port) {
    log("onConnect: port.name=" + port.name);
    if (!port || port.name !== "page") {
      log("ignoring non-page port: " + (port && port.name));
      return;
    }
    pagePorts.add(port);
    broadcastHeartbeat();
    port.onMessage.addListener(function (msg) {
      log("page -> native: " + JSON.stringify(msg).substring(0, 200));
      if (nativePort && msg) {
        try {
          nativePort.postMessage(msg);
        } catch (e) {
          log("nativePort.postMessage THREW: " + e);
          port.postMessage({ id: msg.id, ok: false, error: "native post failed: " + e });
        }
      } else {
        log("dropping page message: nativePort=" + !!nativePort);
        port.postMessage({ id: msg.id, ok: false, error: "native port not connected (" + nativeLastError + ")" });
      }
    });
    port.onDisconnect.addListener(function () {
      log("page port disconnected");
      pagePorts.delete(port);
    });
  });

  connectNative();
})();
