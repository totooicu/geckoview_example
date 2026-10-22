// GeckoView Bridge content script.
// Bridges the page (via window.postMessage, which works across isolated worlds)
// and the extension background (via runtime port).
(function () {
  "use strict";

  function setStage(v) {
    try {
      if (document.documentElement) {
        document.documentElement.setAttribute("data-gv-bridge-stage", v);
      } else {
        setTimeout(function () { setStage(v); }, 50);
      }
    } catch (e) {}
  }

  function markInjected() {
    if (document.documentElement) {
      document.documentElement.setAttribute("data-gv-bridge-injected", "true");
    } else {
      setTimeout(markInjected, 50);
    }
  }
  markInjected();
  setStage("content-started");
  try { console.log("[gv-bridge] content script injected"); } catch (e) {}

  var PAGE_REQUEST = "gv-bridge-request";
  var PAGE_RESPONSE = "gv-bridge-response";
  var PAGE_EVENT = "gv-bridge-event";

  var bgPort = null;
  var retryTimer = null;
  var connectAttempts = 0;

  function postToPage(obj) {
    try { window.postMessage(obj, "*"); } catch (e) {}
  }

  function connect() {
    connectAttempts++;
    setStage("bg-connecting-attempt-" + connectAttempts);
    if (retryTimer) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
    var bgPortLocal;
    try {
      bgPortLocal = browser.runtime.connect({ name: "page" });
    } catch (e) {
      setStage("bg-connect-threw: " + e);
      retryTimer = setTimeout(connect, 1000);
      return;
    }
    if (!bgPortLocal) {
      setStage("bg-connect-returned-null");
      retryTimer = setTimeout(connect, 1000);
      return;
    }
    bgPort = bgPortLocal;
    setStage("bg-port-created");

    bgPort.onMessage.addListener(function (msg) {
      setStage("bg-message-received");
      if (!msg) return;
      if (msg.event === "bg-heartbeat" && msg.data) {
        try {
          document.documentElement.setAttribute("data-gv-bridge-native", JSON.stringify(msg.data));
        } catch (e) {}
        return;
      }
      if (msg.event) {
        postToPage({ type: PAGE_EVENT, event: msg.event, data: msg.data });
      } else {
        postToPage({
          type: PAGE_RESPONSE,
          id: msg.id,
          ok: msg.ok,
          result: msg.result,
          error: msg.error
        });
      }
    });
    bgPort.onDisconnect.addListener(function () {
      setStage("bg-disconnected");
      bgPort = null;
      retryTimer = setTimeout(connect, 1000);
    });
    setStage("bg-connected-waiting");
  }

  // Listen for page requests via window.postMessage (cross-world safe).
  window.addEventListener("message", function (e) {
    var msg = e.data;
    if (!msg || msg.type !== PAGE_REQUEST) return;
    setStage("page-msg-received:" + (msg.action || "?"));
    if (!bgPort) {
      postToPage({
        type: PAGE_RESPONSE,
        id: msg.id,
        ok: false,
        error: "bg port not connected"
      });
      return;
    }
    try {
      bgPort.postMessage({
        id: msg.id,
        action: msg.action,
        params: msg.params || {}
      });
    } catch (err) {
      postToPage({
        type: PAGE_RESPONSE,
        id: msg.id,
        ok: false,
        error: "bridge: " + err
      });
    }
  });

  connect();
})();
