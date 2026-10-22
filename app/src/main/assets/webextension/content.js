// GeckoView Bridge content script.
// Bridges the page (via CustomEvent) and the extension background (via runtime port).
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

  // Mark injection on the shared DOM (content scripts run in an isolated world,
  // so window.* variables are NOT visible to the page).
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

  function postToPage(type, detail) {
    try {
      window.dispatchEvent(new CustomEvent(type, { detail: detail }));
    } catch (e) {}
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
      try { console.log("[gv-bridge] connect threw: " + e); } catch (e2) {}
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
          var d = msg.data;
          document.documentElement.setAttribute("data-gv-bridge-native",
            "connected=" + d.nativeConnected + " attempts=" + d.nativeAttempts + " lastError=" + d.nativeLastError);
        } catch (e) {}
        return; // heartbeats are internal, don't forward to page
      }
      if (msg.event) {
        postToPage(PAGE_EVENT, msg);
      } else {
        postToPage(PAGE_RESPONSE, msg);
      }
    });
    bgPort.onDisconnect.addListener(function () {
      setStage("bg-disconnected");
      try { console.log("[gv-bridge] bg port disconnected"); } catch (e) {}
      bgPort = null;
      retryTimer = setTimeout(connect, 1000);
    });
    setStage("bg-connected-waiting");
    try { console.log("[gv-bridge] bg port connected"); } catch (e) {}
  }

  window.addEventListener(PAGE_REQUEST, function (e) {
    var req = e && e.detail;
    if (!req || !bgPort) {
      postToPage(PAGE_RESPONSE, {
        id: req ? req.id : "?",
        ok: false,
        error: "bridge: bg port not connected (stage=" +
          (document.documentElement && document.documentElement.getAttribute("data-gv-bridge-stage")) + ")"
      });
      return;
    }
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
