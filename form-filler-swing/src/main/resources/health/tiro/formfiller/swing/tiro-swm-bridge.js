/**
 * Tiro SWM Bridge v0.3.0
 * Connects <tiro-form-filler> to a SMART Web Messaging host.
 *
 * Transport-agnostic: the host adapter must call
 *   SmartWebMessaging.init(sendFn)
 * where sendFn(message) delivers a message object to the host.
 *
 * The bridge also injects the harness's embedded @tiro-health/web-sdk bundle (GH-24) from
 * window.__tiroSdkUrl, which BridgeScriptLoader defines in a preamble ahead of this script.
 * The page must not carry an SDK script tag of its own — init() reports what actually loaded
 * in the handshake's `client` field, and the host refuses the session on anything but ours.
 */
(function () {
  "use strict";

  // Prevent re-execution if script is loaded multiple times (e.g., CEF multi-frame loads)
  if (window.__swmBridgeLoaded) return;
  window.__swmBridgeLoaded = true;

  var FORM_FILLER_SELECTOR = "tiro-form-filler";
  var MESSAGING_HANDLE = "smart-web-messaging";
  var HANDSHAKE_RETRY_MS = 1000;
  var HANDSHAKE_TIMEOUT_MS = 30000;
  var REQUEST_TIMEOUT_MS = 30000;

  var pendingRequests = new Map();
  var context = null;

  // ===========================================
  // Transport
  // ===========================================

  var _sendFn = null;

  function generateMessageId() {
    return crypto.randomUUID();
  }

  function sendMessage(message) {
    if (!_sendFn) {
      console.warn("[SWM] No transport configured");
      return;
    }
    console.log("[SWM] Sending:", message.messageType || "response", message);
    _sendFn(message);
  }

  function sendResponse(responseToMessageId, payload) {
    sendMessage({
      messageId: generateMessageId(),
      responseToMessageId: responseToMessageId,
      additionalResponsesExpected: false,
      payload: payload,
    });
  }

  function sendRequest(messageType, payload) {
    return new Promise(function (resolve, reject) {
      var messageId = generateMessageId();
      pendingRequests.set(messageId, { resolve: resolve, reject: reject });

      sendMessage({
        messageId: messageId,
        messagingHandle: MESSAGING_HANDLE,
        messageType: messageType,
        payload: payload || {},
      });

      setTimeout(function () {
        if (pendingRequests.has(messageId)) {
          pendingRequests.delete(messageId);
          reject(new Error("Request timeout: " + messageType));
        }
      }, REQUEST_TIMEOUT_MS);
    });
  }

  function sendEvent(messageType, payload) {
    sendMessage({
      messageId: generateMessageId(),
      messagingHandle: MESSAGING_HANDLE,
      messageType: messageType,
      payload: payload || {},
    });
  }

  // ===========================================
  // Incoming messages
  // ===========================================

  function handleMessage(message) {
    // Parse JSON strings (JxBrowser/Equo deliver strings, WebView2/iframe deliver objects)
    if (typeof message === "string") {
      try {
        message = JSON.parse(message);
      } catch (e) {
        console.error("[SWM] Failed to parse message:", e);
        return;
      }
    }
    console.log("[SWM] Received:", message.messageType || "response", message);

    // Response to a pending request
    if (message.responseToMessageId) {
      var pending = pendingRequests.get(message.responseToMessageId);
      if (pending) {
        if (message.payload && message.payload.$type === "error") {
          pending.reject(new Error(message.payload.errorMessage));
        } else {
          pending.resolve(message.payload);
        }
        if (!message.additionalResponsesExpected) {
          pendingRequests.delete(message.responseToMessageId);
        }
      }
      return;
    }

    // Host-initiated message
    if (message.messageType) {
      handleHostMessage(message);
    }
  }

  function handleHostMessage(message) {
    // Typed so `tsc --checkJs` validates the bridge's calls into the element (submit,
    // setAttribute, questionnaire) against @tiro-health/web-sdk. Intersected with
    // HTMLElement because the published .d.ts imports its base class from `lit`, which
    // a type-only consumer doesn't install.
    var formFiller =
      /** @type {import("@tiro-health/web-sdk").TiroFormFiller & HTMLElement} */ (
        document.querySelector(FORM_FILLER_SELECTOR)
      );
    var handled = true;

    switch (message.messageType) {
      case "sdc.configure":
        console.log("[SWM] Configuration received");
        break;

      case "sdc.configureContext":
        context = message.payload;
        applyLaunchContext(formFiller, context);
        console.log("[SWM] Context updated");
        break;

      case "sdc.displayQuestionnaire":
        displayQuestionnaire(formFiller, message.payload);
        break;

      case "ui.form.requestSubmit":
        if (formFiller && formFiller.questionnaire) {
          // Map the host intent ("finalize" | "save-draft") to the form-filler's
          // target status. The form still owns the completed -> amended promotion
          // (via originate provenance) and the required-field validation skip for
          // in-progress drafts.
          var intent = message.payload && message.payload.intent;
          if (intent === "save-draft") {
            formFiller.submit({ status: "in-progress" });
          } else {
            formFiller.submit();
          }
        }
        break;

      case "ui.form.persist":
        break;

      default:
        handled = false;
        sendResponse(message.messageId, {
          $type: "error",
          errorMessage: "Unknown message type: " + message.messageType,
          errorType: "UnknownMessageTypeException",
        });
        break;
    }

    if (handled) {
      sendResponse(message.messageId, { $type: "base" });
    }
  }

  // ===========================================
  // Questionnaire display
  // ===========================================

  function applyLaunchContext(formFiller, ctx) {
    if (!formFiller || !ctx || !Array.isArray(ctx.launchContext)) return;
    var launchContext = {};
    ctx.launchContext.forEach(function (item) {
      if (item.name && item.contentResource) {
        launchContext[item.name] = item.contentResource;
      }
    });
    if (Object.keys(launchContext).length > 0) {
      formFiller.setAttribute(
        "launch-context",
        JSON.stringify(launchContext)
      );
    }
  }

  function displayQuestionnaire(formFiller, payload) {
    var questionnaire = payload.questionnaire;
    var questionnaireResponse = payload.questionnaireResponse;

    if (payload.context) {
      context = Object.assign({}, context, payload.context);
    }

    if (!questionnaire) {
      console.error("[SWM] No questionnaire in payload");
      return;
    }

    // Set launch context from host context
    applyLaunchContext(formFiller, context);

    // Set initial response if provided
    if (questionnaireResponse) {
      formFiller.setAttribute(
        "initial-response",
        JSON.stringify(questionnaireResponse)
      );
    }

    // Set questionnaire last (triggers render)
    formFiller.setAttribute(
      "questionnaire",
      typeof questionnaire === "string"
        ? questionnaire
        : JSON.stringify(questionnaire)
    );
  }

  // ===========================================
  // Form submission
  // ===========================================

  function sanitizeNulls(value) {
    if (value === null) return undefined;
    if (typeof value !== "object") return value;
    if (Array.isArray(value)) {
      return value.map(sanitizeNulls).filter(function (v) {
        return v !== undefined;
      });
    }
    var result = {};
    for (var key in value) {
      if (!value.hasOwnProperty(key)) continue;
      var sanitized = sanitizeNulls(value[key]);
      if (sanitized !== undefined) result[key] = sanitized;
    }
    return result;
  }

  /** @param {import("@tiro-health/web-sdk").TiroFormFiller & HTMLElement} formFiller */
  function submitForm(formFiller, response) {
    if (!response.status) response.status = "completed";
    response = sanitizeNulls(response);

    var doSubmit = function () {
      sendRequest("form.submitted", {
        response: response,
        outcome: {
          resourceType: "OperationOutcome",
          issue: [
            {
              severity: "information",
              code: "informational",
              diagnostics: "Form submitted successfully",
            },
          ],
        },
      })
        .then(function () {
          console.log("[SWM] Form submitted");
        })
        .catch(function (err) {
          console.error("[SWM] Submission failed:", err);
        });
    };

    // Generate narrative if SDC client is available
    if (formFiller.sdcClient && formFiller.sdcClient.generateNarrative) {
      formFiller.sdcClient
        .generateNarrative(response)
        .then(function (narrative) {
          response.text = narrative;
          doSubmit();
        })
        .catch(function () {
          doSubmit();
        });
    } else {
      doSubmit();
    }
  }

  // ===========================================
  // Embedded web-sdk injection (GH-24)
  // ===========================================

  // The embedded, validated @tiro-health/web-sdk the harness extracted and serves. The host
  // injects the URL as window.__tiroSdkUrl ahead of this script, because the file name carries
  // the SDK version for cache-busting and a static asset cannot know it. There is no default:
  // the only URL that ever works is the one the host publishes, so a missing injection is a
  // load failure, reported as such rather than sent to a 404 whose message would blame the
  // page's hosting.
  //
  // Resolves with the source reported at handshake: "embedded" | "collision" | "error".
  // The host refuses the session on the latter two.
  function bootSdk() {
    // A foreign element definition or a page-level SDK script tag means the page still loads
    // its own SDK. Don't inject a second copy — a double customElements.define() throws — and
    // report the collision so the host can refuse with a message that names the cause.
    var foreignElement =
      typeof customElements !== "undefined" && customElements.get(FORM_FILLER_SELECTOR);
    var foreignTag =
      typeof document.querySelector === "function" &&
      document.querySelector('script[src*="tiro-web-sdk"]');
    if (foreignElement || foreignTag) {
      console.error(
        "[SWM] The page loads its own tiro-web-sdk copy. Remove the tiro-web-sdk <script> tag " +
          "from your page — the harness embeds and serves its own validated copy (GH-24)."
      );
      return Promise.resolve("collision");
    }

    var sdkUrl = window.__tiroSdkUrl;
    if (!sdkUrl) {
      console.error(
        "[SWM] window.__tiroSdkUrl was not injected, so there is no SDK to load. The Java host " +
          "defines it in a preamble ahead of this script; a page-only harness must set it too."
      );
      return Promise.resolve("error");
    }

    return new Promise(function (resolve) {
      var script = document.createElement("script");
      script.src = sdkUrl;
      // No crossorigin attribute: the bundle is a file:// sibling of the page, which a plain
      // no-cors classic script load reaches and a CORS-mode load would not.
      script.onload = function () {
        resolve("embedded");
      };
      script.onerror = function () {
        console.error(
          "[SWM] Failed to load the embedded tiro-web-sdk from " +
            sdkUrl +
            " — the form cannot render. A page served over http(s) cannot load a file:// " +
            "script; use the harness's generated page (sdcEndpointAddress) or a local one."
        );
        resolve("error");
      };
      document.head.appendChild(script);
    });
  }

  // What the handshake reports about the SDK actually running in the page. `version` is the
  // element's build-time static, null when the SDK predates it, failed to load, or is foreign.
  function describeClient(source) {
    // Typed as the SDK's own class, so `tsc --checkJs` verifies against @tiro-health/web-sdk
    // that the element still declares a build-time `static version` — the day a release drops
    // it, the contract check goes red instead of this quietly reporting null forever.
    var cls =
      /** @type {typeof import("@tiro-health/web-sdk").TiroFormFiller | undefined} */ (
        /** @type {unknown} */ (
          typeof customElements !== "undefined"
            ? customElements.get(FORM_FILLER_SELECTOR)
            : undefined
        )
      );
    return {
      name: "tiro-web-sdk",
      version: cls && typeof cls.version === "string" ? cls.version : null,
      source: source,
    };
  }

  // ===========================================
  // Handshake
  // ===========================================

  function retryHandshake(client) {
    return new Promise(function (resolve, reject) {
      var startTime = Date.now();
      var attemptIds = [];
      var resolved = false;

      function cleanup() {
        attemptIds.forEach(function (id) {
          pendingRequests.delete(id);
        });
      }

      function onSuccess(payload) {
        if (resolved) return;
        resolved = true;
        cleanup();
        resolve(payload);
      }

      // An error ack is the host refusing the session — it does that when the page is not
      // running the embedded SDK (GH-24). Terminal, not a retry: the host has already decided,
      // and every further attempt gets the same answer. Swallowing it here (this used to be a
      // no-op) left the page retrying for the full 30s and then reporting a timeout, which
      // reads as "the host never answered" — the opposite of what happened.
      function onRefused(error) {
        if (resolved) return;
        resolved = true;
        cleanup();
        reject(error);
      }

      function attempt() {
        if (resolved) return;
        var messageId = generateMessageId();
        attemptIds.push(messageId);
        pendingRequests.set(messageId, {
          resolve: onSuccess,
          reject: onRefused,
        });

        sendMessage({
          messageId: messageId,
          messagingHandle: MESSAGING_HANDLE,
          messageType: "status.handshake",
          payload: { client: client },
        });

        setTimeout(function () {
          if (!resolved && Date.now() - startTime < HANDSHAKE_TIMEOUT_MS) {
            attempt();
          }
        }, HANDSHAKE_RETRY_MS);
      }

      setTimeout(function () {
        if (!resolved) {
          cleanup();
          reject(new Error("Handshake timeout"));
        }
      }, HANDSHAKE_TIMEOUT_MS);

      attempt();
    });
  }

  // ===========================================
  // Init
  // ===========================================

  function wireFormFiller(formFiller) {
    formFiller.addEventListener("tiro-submit", function (event) {
      submitForm(formFiller, event.detail.response);
    });
  }

  function init(sendFn) {
    if (_sendFn) return; // already initialized in this page context
    if (typeof sendFn !== "function") {
      console.error("[SWM] init() requires a sendFn argument");
      return;
    }

    _sendFn = sendFn;
    console.log("[SWM] Transport configured");

    // The SDK loads before anything else, so the element is upgraded by the time we wire it
    // and defined by the time the handshake reads its version. A failed or foreign load is
    // still handshaked — reporting it is how the host learns to refuse the session.
    bootSdk().then(function (source) {
      var formFiller = document.querySelector(FORM_FILLER_SELECTOR);
      if (formFiller) {
        wireFormFiller(formFiller);
      }

      retryHandshake(describeClient(source))
        .then(function () {
          console.log("[SWM] Connected");
        })
        .catch(function (err) {
          console.error("[SWM] Handshake failed:", err);
        });
    });
  }

  // Global receive handler for async Java→JS messages.
  // Java adapters call: executeJavaScript("window.swmReceiveMessage('...')")
  window.swmReceiveMessage = function (jsonStr) {
    handleMessage(jsonStr);
  };

  // Expose API globally so the host adapter can call init(sendFn)
  // and HTML buttons can trigger save/submit.
  window.SmartWebMessaging = {
    init: init,
    saveProgress: function () {
      var formFiller =
        /** @type {import("@tiro-health/web-sdk").TiroFormFiller & HTMLElement} */ (
          document.querySelector(FORM_FILLER_SELECTOR)
        );
      if (formFiller && formFiller.questionnaire) {
        // Route through the form's real submit pipeline (required-field validation
        // skip, provenance) instead of stamping response.status externally. The form
        // emits tiro-submit with status "in-progress", which submitForm forwards.
        formFiller.submit({ status: "in-progress" });
      }
    },
    validate: function () {
      var formFiller =
        /** @type {import("@tiro-health/web-sdk").TiroFormFiller & HTMLElement} */ (
          document.querySelector(FORM_FILLER_SELECTOR)
        );
      if (formFiller) {
        formFiller.submit();
      }
    },
  };
})();
