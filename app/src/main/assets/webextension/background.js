// Bridge background script (sendNativeMessage mode).
// Forwards page content script messages to the native app via
// browser.runtime.sendNativeMessage("bridge", msg) — request/response, no persistent port.
(function () {
  "use strict";

  function log(msg) {
    try { console.log("[gv-bridge-bg] " + msg); } catch (e) {}
  }

  log("background started (sendNativeMessage mode)");

  var pagePorts = new Set();
  var nativeAttempts = 0;
  var nativeLastError = "(not attempted)";
  var nativeLastOk = 0;

  function broadcastHeartbeat() {
    for (var p of pagePorts) {
      try {
        p.postMessage({
          event: "bg-heartbeat",
          data: {
            mode: "sendNativeMessage",
            nativeAttempts: nativeAttempts,
            nativeLastError: nativeLastError,
            nativeLastOk: nativeLastOk,
            pagePorts: pagePorts.size
          }
        });
      } catch (e) {}
    }
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
      nativeAttempts++;
      nativeLastError = "(sending...)";
      broadcastHeartbeat();

      browser.runtime.sendNativeMessage("bridge", msg)
        .then(function (resp) {
          nativeLastOk++;
          nativeLastError = "(ok)";
          log("native -> page: " + JSON.stringify(resp).substring(0, 200));
          broadcastHeartbeat();
          try { port.postMessage(resp); } catch (e) {}
        })
        .catch(function (err) {
          var msg2 = (err && err.message) ? err.message : String(err);
          nativeLastError = "sendNativeMessage failed: " + msg2;
          log("sendNativeMessage FAILED: " + msg2);
          broadcastHeartbeat();
          try {
            port.postMessage({ id: msg.id, ok: false, error: nativeLastError });
          } catch (e) {}
        });
    });

    port.onDisconnect.addListener(function () {
      log("page port disconnected");
      pagePorts.delete(port);
    });
  });
})();
