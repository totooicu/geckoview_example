// Bridge background script (MV3).
// Owns the native messaging port (connectNative) and routes messages between
// page content scripts (runtime.onConnect ports) and the native app.
(function () {
  "use strict";

  var nativePort = null;
  var pagePorts = new Set();

  function connectNative() {
    try {
      nativePort = browser.runtime.connectNative("bridge");
    } catch (e) {
      setTimeout(connectNative, 1000);
      return;
    }
    if (!nativePort) {
      setTimeout(connectNative, 1000);
      return;
    }
    nativePort.onMessage.addListener(function (msg) {
      // native -> all pages
      for (var p of pagePorts) {
        try {
          p.postMessage(msg);
        } catch (e) {
          /* ignore */
        }
      }
    });
    nativePort.onDisconnect.addListener(function () {
      nativePort = null;
      setTimeout(connectNative, 1000);
    });
  }

  browser.runtime.onConnect.addListener(function (port) {
    if (!port || port.name !== "page") {
      return;
    }
    pagePorts.add(port);
    port.onMessage.addListener(function (msg) {
      // page -> native
      if (nativePort && msg) {
        try {
          nativePort.postMessage(msg);
        } catch (e) {
          /* ignore */
        }
      }
    });
    port.onDisconnect.addListener(function () {
      pagePorts.delete(port);
    });
  });

  connectNative();
})();
