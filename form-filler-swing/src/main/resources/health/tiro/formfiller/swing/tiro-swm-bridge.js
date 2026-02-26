/**
 * Tiro SWM Bridge v0.2.0
 * Connects <tiro-form-filler> to a SMART Web Messaging host.
 *
 * Transport-agnostic: the host adapter must call
 *   SmartWebMessaging.init(sendFn)
 * where sendFn(message) delivers a message object to the host.
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
    var formFiller = document.querySelector(FORM_FILLER_SELECTOR);
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
          formFiller.submit();
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
  // Handshake
  // ===========================================

  function retryHandshake() {
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

      function attempt() {
        if (resolved) return;
        var messageId = generateMessageId();
        attemptIds.push(messageId);
        pendingRequests.set(messageId, {
          resolve: onSuccess,
          reject: function () {},
        });

        sendMessage({
          messageId: messageId,
          messagingHandle: MESSAGING_HANDLE,
          messageType: "status.handshake",
          payload: {},
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

  var latestResponse = null;

  function wireFormFiller(formFiller) {
    formFiller.addEventListener("tiro-update", function (event) {
      latestResponse = event.detail.response;
    });
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

    // Wire up form-filler events on the initial element
    var formFiller = document.querySelector(FORM_FILLER_SELECTOR);
    if (formFiller) {
      wireFormFiller(formFiller);
    }

    retryHandshake()
      .then(function () {
        console.log("[SWM] Connected");
      })
      .catch(function (err) {
        console.error("[SWM] Handshake failed:", err);
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
      var formFiller = document.querySelector(FORM_FILLER_SELECTOR);
      if (latestResponse && formFiller) {
        var response = JSON.parse(JSON.stringify(latestResponse));
        response.status = "in-progress";
        submitForm(formFiller, response);
      }
    },
    validate: function () {
      var formFiller = document.querySelector(FORM_FILLER_SELECTOR);
      if (formFiller) {
        formFiller.submit();
      }
    },
  };
})();
