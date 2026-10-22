// GeckoView Bridge content script (MV3).
// Bridges the page (via CustomEvent) and the extension background (via runtime port).
// Protocol:
//   page -> this: window CustomEvent "gv-bridge-request"  {id, action, params}
//   this -> background: port.postMessage({id, action, params})
//   background -> this: port.onMessage  {id, ok, result|error}  |  {event, data}
//   this -> page: CustomEvent "gv-bridge-response" | "gv-bridge-event"
(function () {
  "use strict";

  // Diagnostic: visible to the page so the test page can report injection status.
  window.__gvBridgeInjected = true;
  try {
    console.log("[gv-bridge] content script injected");
  } catch (e) {
    /* ignore */
  }

  var PAGE_REQUEST = "gv-bridge-request";
  var PAGE_RESPONSE = "gv-bridge-response";
  var PAGE_EVENT = "gv-bridge-event";

  var bgPort = null;
  var retryTimer = null;

  function postToPage(type, detail) {
    try {
      window.dispatchEvent(new CustomEvent(type, { detail: detail }));
    } catch (e) {
      /* ignore */
    }
  }

  function connect() {
    if (retryTimer) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
    try {
      bgPort = browser.runtime.connect({ name: "page" });
    } catch (e) {
      retryTimer = setTimeout(connect, 1000);
      return;
    }
    if (!bgPort) {
      retryTimer = setTimeout(connect, 1000);
      return;
    }

    bgPort.onMessage.addListener(function (msg) {
      if (!msg) return;
      if (msg.event) {
        postToPage(PAGE_EVENT, msg);
      } else {
        postToPage(PAGE_RESPONSE, msg);
      }
    });
    bgPort.onDisconnect.addListener(function () {
      bgPort = null;
      retryTimer = setTimeout(connect, 1000);
    });
  }

  window.addEventListener(PAGE_REQUEST, function (e) {
    var req = e && e.detail;
    if (!req || !bgPort) return;
    try {
      bgPort.postMessage({
        id: req.id,
        action: req.action,
        params: req.params || {}
      });
    } catch (err) {
      postToPage(PAGE_RESPONSE, {
        id: req.id,
        ok: false,
        error: "bridge: " + err
      });
    }
  });

  connect();
})();
