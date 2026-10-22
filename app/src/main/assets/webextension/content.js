// GeckoView Bridge content script.
// Bridges the page (via CustomEvent) and the native app (via runtime port).
// Protocol:
//   page -> this: window CustomEvent "gv-bridge-request"  {id, action, params}
//   this -> native: port.postMessage({id, action, params})
//   native -> this: port.onMessage   {id, ok, result|error}  |  {event, data}
//   this -> page: CustomEvent "gv-bridge-response" | "gv-bridge-event"
(function () {
  "use strict";

  var PORT_NAME = "bridge";
  var PAGE_REQUEST = "gv-bridge-request";
  var PAGE_RESPONSE = "gv-bridge-response";
  var PAGE_EVENT = "gv-bridge-event";

  var port = null;
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
      port = browser.runtime.connectNative(PORT_NAME);
    } catch (e) {
      retryTimer = setTimeout(connect, 1000);
      return;
    }
    if (!port) {
      retryTimer = setTimeout(connect, 1000);
      return;
    }

    port.onMessage.addListener(function (msg) {
      if (!msg) return;
      if (msg.event) {
        postToPage(PAGE_EVENT, msg);
      } else {
        postToPage(PAGE_RESPONSE, msg);
      }
    });
    port.onDisconnect.addListener(function () {
      port = null;
      retryTimer = setTimeout(connect, 1000);
    });
  }

  window.addEventListener(PAGE_REQUEST, function (e) {
    var req = e && e.detail;
    if (!req || !port) return;
    try {
      port.postMessage({
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

  // MessageChannel routing: support iframe isolation by forwarding via window.parent
  window.addEventListener("message", function (e) {
    var m = e.data;
    if (!m || !m.__gvBridge) return;
    if (m.__gvBridge === "request") {
      // page sent a request from same-frame; re-dispatch as CustomEvent
      window.dispatchEvent(
        new CustomEvent(PAGE_REQUEST, { detail: m.req })
      );
    }
  });

  connect();
})();
