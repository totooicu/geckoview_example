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

  function connectNative() {
    nativeAttempts++;
    log("connectNative attempt " + nativeAttempts);
    try {
      nativePort = browser.runtime.connectNative("bridge");
    } catch (e) {
      log("connectNative THREW: " + e);
      setTimeout(connectNative, 1000);
      return;
    }
    if (!nativePort) {
      log("connectNative returned null");
      setTimeout(connectNative, 1000);
      return;
    }
    log("nativePort created, waiting for onMessage/onDisconnect");

    nativePort.onMessage.addListener(function (msg) {
      log("native -> pages: " + JSON.stringify(msg).substring(0, 200));
      for (var p of pagePorts) {
        try { p.postMessage(msg); } catch (e) {}
      }
    });
    nativePort.onDisconnect.addListener(function () {
      var err = browser.runtime.lastError;
      log("nativePort disconnected. lastError=" + (err ? err.message : "none"));
      nativePort = null;
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
    // Heartbeat: tell content script the background is alive.
    try {
      port.postMessage({ event: "bg-heartbeat", data: { nativeConnected: !!nativePort, pagePorts: pagePorts.size } });
    } catch (e) {}
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
        port.postMessage({ id: msg.id, ok: false, error: "native port not connected" });
      }
    });
    port.onDisconnect.addListener(function () {
      log("page port disconnected");
      pagePorts.delete(port);
    });
  });

  connectNative();
})();
