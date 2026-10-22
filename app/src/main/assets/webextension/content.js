// GeckoView Bridge content script.
// Directly calls browser.runtime.sendNativeMessage (no background hop),
// because MV2 background's environmentType may be UNKNOWN and getDelegate
// only resolves global delegate for environmentType==1.
// Content script has a non-null sender.session, so it takes the session-level
// delegate path, which we register via SessionController.setMessageDelegate.
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
  try { console.log("[gv-bridge] content script injected (direct mode)"); } catch (e) {}

  var PAGE_REQUEST = "gv-bridge-request";
  var PAGE_RESPONSE = "gv-bridge-response";
  var PAGE_EVENT = "gv-bridge-event";

  function postToPage(obj) {
    try { window.postMessage(obj, "*"); } catch (e) {}
  }

  // Listen for page requests via window.postMessage (cross-world safe).
  window.addEventListener("message", function (e) {
    var msg = e.data;
    if (!msg || msg.type !== PAGE_REQUEST) return;
    setStage("page-msg:" + (msg.action || "?"));

    try {
      browser.runtime.sendNativeMessage("bridge", {
        id: msg.id,
        action: msg.action,
        params: msg.params || {}
      }).then(function (resp) {
        setStage("native-ok:" + (resp && resp.ok));
        try { console.log("[gv-bridge] native resp: " + JSON.stringify(resp).substring(0, 200)); } catch (e) {}
        if (resp && resp.event) {
          postToPage({ type: PAGE_EVENT, event: resp.event, data: resp.data });
        } else {
          postToPage({
            type: PAGE_RESPONSE,
            id: resp ? resp.id : msg.id,
            ok: resp ? resp.ok : false,
            result: resp ? resp.result : null,
            error: resp ? resp.error : "empty response"
          });
        }
      }).catch(function (err) {
        var emsg = (err && err.message) ? err.message : String(err);
        setStage("native-err:" + emsg);
        try { console.log("[gv-bridge] sendNativeMessage FAILED: " + emsg); } catch (e) {}
        postToPage({
          type: PAGE_RESPONSE,
          id: msg.id,
          ok: false,
          error: "native: " + emsg
        });
      });
    } catch (err) {
      setStage("bridge-threw:" + err);
      postToPage({
        type: PAGE_RESPONSE,
        id: msg.id,
        ok: false,
        error: "bridge: " + err
      });
    }
  });
})();
