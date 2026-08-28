# Java Integration Harness

Embed the [Tiro.health form filler](https://tiro.health) in Java desktop applications using SMART Web Messaging.

## Features

- Parse and handle SMART Web Messaging messages
- SDC (Structured Data Capture) operations
- Form submission handling
- Event-driven architecture
- Multi-version FHIR support (R4 and R5)
- Embedded browser UI modules (JxBrowser and Equo Chromium adapters)

## Installation

Available on [Maven Central](https://repo1.maven.org/maven2/health/tiro/).

Pick the module matching your FHIR version:

**FHIR R5:**

```xml
<dependency>
    <groupId>health.tiro</groupId>
    <artifactId>smart-web-messaging-r5</artifactId>
    <version>2.0.0</version>
</dependency>
```

**FHIR R4:**

```xml
<dependency>
    <groupId>health.tiro</groupId>
    <artifactId>smart-web-messaging-r4</artifactId>
    <version>2.0.0</version>
</dependency>
```

Both modules transitively include `smart-web-messaging-core`, so you only need one dependency.

## Usage

### Basic Setup

```java
// R5 example — for R4, change the import to health.tiro.swm.r4.SmartMessageHandler
import health.tiro.swm.r5.SmartMessageHandler;
import health.tiro.swm.r5.R5SmartMessageListener;
import org.hl7.fhir.r5.model.*;

// Create handler
SmartMessageHandler handler = new SmartMessageHandler();

// Add event listeners (typed adapter — no casts needed)
handler.addListener(new R5SmartMessageListener() {
    @Override
    public void onFormSubmitted(QuestionnaireResponse response, OperationOutcome outcome) {
        // Process the submitted form — fully typed
    }

    @Override
    public void onCloseApplication(CloseApplicationEvent event) {
        // Close the form filler
    }
});
```

### Embedded Browser (FormFiller)

The easiest way to embed a SMART Web Messaging browser is using `FormFiller` with a browser adapter:

```java
import health.tiro.swm.r5.SmartMessageHandler;
import health.tiro.formfiller.swing.*;
import health.tiro.formfiller.swing.jxbrowser.*;  // or .equo.*

// 1. Create a browser adapter (pick one)
EmbeddedBrowser browser = new JxBrowserAdapter(
    JxBrowserConfig.builder().licenseKey("YOUR-KEY").build()
);
// OR: new EquoBrowserAdapter()

// 2. Create the FHIR handler (pick your version)
SmartMessageHandler handler = new SmartMessageHandler();

// 3. Create the viewer (uses the built-in default page)
FormFillerConfig config = FormFillerConfig.builder()
    .sdcEndpointAddress("http://localhost:8000/fhir/r5")
    .build();
// Or bring your own page (local file, and no tiro-web-sdk script tag — see below):
// FormFillerConfig config = FormFillerConfig.builder()
//     .targetUrl("file:///opt/ehr/form-filler.html")
//     .build();
FormFiller viewer = new FormFiller(config, browser, handler);

// 4. Listen for events
viewer.addFormFillerListener(new FormFillerListener() {
    @Override
    public void onFormSubmitted(IBaseResource response, IBaseResource outcome) {
        QuestionnaireResponse qr = (QuestionnaireResponse) response;
        // process the submitted form
    }

    @Override
    public void onCloseRequested() {
        // close the window
    }
});

// 5. Add to your Swing UI
frame.add(viewer.getComponent(), BorderLayout.CENTER);

// 6. Display a questionnaire (messages are queued until handshake completes)
handler.sendSdcDisplayQuestionnaireAsync(
    "http://example.org/Questionnaire/intake",
    null, patient, encounter, author, null
);
```

Install the UI module matching your browser engine:

**JxBrowser:**

```xml
<dependency>
    <groupId>health.tiro</groupId>
    <artifactId>form-filler-swing-jxbrowser</artifactId>
    <version>2.0.0</version>
</dependency>
```

**Equo Chromium:**

```xml
<dependency>
    <groupId>health.tiro</groupId>
    <artifactId>form-filler-swing-equo</artifactId>
    <version>2.0.0</version>
</dependency>
```

Both transitively include `form-filler-swing` and `smart-web-messaging-core`. You also need the R4 or R5 module for your FHIR handler, plus the browser engine dependency itself (JxBrowser or Equo Chromium).

### Sending SDC Messages

```java
// Display a questionnaire
Patient patient = new Patient();
patient.setId("patient-123");

handler.sendSdcDisplayQuestionnaireAsync(
    "http://example.org/Questionnaire/intake-form",  // canonical URL
    null,  // existing QuestionnaireResponse (optional)
    patient,
    null,  // Encounter (optional)
    null,  // Practitioner (optional)
    response -> {
        // Handle response
        System.out.println("Questionnaire displayed");
    }
);

// Request form submission
handler.sendFormRequestSubmitAsync(response -> {
    System.out.println("Form submission requested");
});

// Configure SDC context
handler.sendSdcConfigureContextAsync(
    patient,
    null,  // Encounter
    null,  // Practitioner/Author
    response -> {
        System.out.println("Context configured");
    }
);
```

## Module Structure

| Module | Artifact | Description |
|--------|----------|-------------|
| Core | `smart-web-messaging-core` | Shared logic, FHIR-version-independent. Depends on `hapi-fhir-base` only. |
| R4 | `smart-web-messaging-r4` | FHIR R4 typed API. Depends on core + `hapi-fhir-structures-r4`. |
| R5 | `smart-web-messaging-r5` | FHIR R5 typed API. Depends on core + `hapi-fhir-structures-r5`. |
| Swing | `form-filler-swing` | `FormFiller` controller + `EmbeddedBrowser` interface. Depends on core. |
| Swing JxBrowser | `form-filler-swing-jxbrowser` | JxBrowser adapter. Depends on swing + JxBrowser (provided). |
| Swing Equo | `form-filler-swing-equo` | Equo Chromium adapter. Depends on swing + Equo Chromium (provided). |
| SDC Client Core | `sdc-client-core` | FHIR-version-agnostic core for the stateless SDC server operations. Depends on `hapi-fhir-base` + Apache HttpClient. |
| SDC Client R5 | `sdc-client-r5` | FHIR R5 binding: `SdcClient` for `$validate`/`$extract`. Depends on core + `hapi-fhir-structures-r5`. |

## SDC Client

`SdcClient` (module `sdc-client-r5`) is a thin, strongly-typed client over the **stateless SDC server** FHIR operations — call them directly instead of hand-building request bodies and parsing raw responses. Separate from the messaging/viewer modules (an HTTP/FHIR client, not the embedded-UI bridge).

```java
import health.tiro.sdc.client.r5.SdcClient;
import org.hl7.fhir.r5.model.*;

try (SdcClient sdc = new SdcClient("https://host/fhir/r5")) {   // optionally pass a CloseableHttpClient for TLS/proxy
    OperationOutcome outcome = sdc.validate(questionnaireResponse);   // POST QuestionnaireResponse/$validate
    Bundle extracted        = sdc.extract(questionnaireResponse);     // POST QuestionnaireResponse/$extract
}
```

- **R5-only**: these SDC operations exist only on `/fhir/r5` (a future R4 server would be one new `sdc-client-r4` binding). **No auth** — the base URL is sufficient. Calls are **blocking** (HAPI/Apache HttpClient are synchronous).
- A validation *failure* comes back as `OperationOutcome` issues; transport/server errors (non-2xx) throw `SdcOperationException` (carrying the status + any server outcome). Responses are parsed leniently, tolerating elements/codes a newer server emits.
- **Use one SDC base for both**: `baseUrl` here and the viewer's `FormFillerConfig.sdcEndpointAddress` are the same concept — the SDC server. A host that embeds the form **and** calls the client should configure the address once and pass it to both. The client has no default base (you must pass one) to avoid silently diverging from a configured viewer.
- `$extract` returns a transaction `Bundle`: the resources the answers produce (Tiro's template questionnaires yield a `Composition` with per-section narrative; definition-based ones yield structured resources), plus the source QR and a `Provenance`. `$populate` is tracked separately (#20).

## Message Types Supported

### Inbound (from WebView)
- `status.handshake` - Handshake from embedded app
- `form.submitted` - Form submission with QuestionnaireResponse
- `ui.done` - Application close request

### Outbound (to WebView)
- `ui.form.requestSubmit` - Request form submission
- `ui.form.persist` - Request form persistence
- `sdc.configure` - Configure SDC settings
- `sdc.configureContext` - Configure launch context
- `sdc.displayQuestionnaire` - Display a questionnaire

## JS Bridge

The SWM bridge JavaScript is bundled in the library and **injected automatically** by each browser adapter after page load. The HTML page does not need to include any bridge script.

Each adapter injects the bridge and initializes it with a transport-specific `sendFn`:

- **JxBrowser** — exposes `window.javaBridge`, calls `SmartWebMessaging.init(sendFn)` where `sendFn` uses `javaBridge.postMessage(json)`
- **Equo Chromium** — calls `SmartWebMessaging.init(sendFn)` where `sendFn` uses iframe URL scheme (`swm://postMessage/...`)
- **WebView2 (.NET)** — same pattern with `chrome.webview.postMessage(msg)`

Java→JS messages are delivered via `window.swmReceiveMessage(json)`, which the bridge registers globally.

### The embedded frontend

**You pick one version — the harness.** It embeds the exact `@tiro-health/web-sdk` bundle it was validated against, extracts it next to the page, and the bridge injects it. There is no SDK URL to configure, no CDN egress, and no way for the bridge and the element to drift apart.

- The pin lives in [`build/web-sdk/package.json`](build/web-sdk/README.md); the bundle and its generated `web-sdk.version.json` are committed under `form-filler-swing/src/main/resources/`, so `git clone && mvn package` needs no token.
- The bridge is type-checked against that exact version on every PR and again at release ([`build/bridge-contract/`](build/bridge-contract/README.md)).
- `save-draft` (`requestSubmit("save-draft")`) maps to the element's `submit({ status: "in-progress" })`, added in web-sdk 0.3.0. The embedded bundle is well past that, so the option works — this is no longer a floor you have to check.

**Your page must not load `tiro-web-sdk` itself.** With a custom `targetUrl`, delete the `<script src="…tiro-web-sdk…">` tag; the page is markup and branding only. The bridge reports what actually loaded in the handshake, and the harness refuses the session with a `WebSdkLoadException` if the page ran its own copy (`collision`) or the embedded bundle could not load (`error`).

One consequence worth knowing: a `file://` script only loads into a `file://` document, so a `targetUrl` served over **http(s) cannot run the embedded bundle** and is refused. Use the generated page (`sdcEndpointAddress`) or ship your page as a local file.

## Examples

See the [`examples/`](examples/) directory for runnable demo applications:

- **[JxBrowser — minimal](examples/jxbrowser/src/main/java/health/tiro/examples/jxbrowser/Main.java)** — basic form filler with JxBrowser (FHIR R4)
- **[JxBrowser — complete](examples/jxbrowser/src/main/java/health/tiro/examples/jxbrowser/CompleteExample.java)** — EHR-style UI with patient context, template switching, and saved progress
- **[Equo Chromium](examples/equo/src/main/java/health/tiro/examples/equo/Main.java)** — basic form filler with Equo Chromium (FHIR R5)

**Running the examples:**

```bash
# JxBrowser (requires a license key)
cd examples/jxbrowser
mvn compile exec:java \
  -Djxbrowser.license.key=YOUR-LICENSE-KEY \
  -Dexec.mainClass=health.tiro.examples.jxbrowser.Main

# Equo Chromium
cd examples/equo
mvn compile exec:exec
```

## Sentry Integration (Optional)

The `form-filler-swing` module has built-in support for [Sentry](https://sentry.io) tracing. When Sentry is on the classpath and initialized, the library automatically creates a transaction per `FormFiller` session with spans for:

- JS bridge injection
- Every SMART Web Messaging message sent and received (with full JSON payload)
- Handshake completion
- Form submission

Spans are created on a shared transaction instance, so they work across all threads (Swing EDT, browser render thread, message handler thread).

### Setup

**1. Add the Sentry dependency to your application:**

```xml
<dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry</artifactId>
    <version>8.33.0</version>
</dependency>
```

**2. Initialize Sentry before creating the `FormFiller`:**

```java
Sentry.init(options -> {
    options.setDsn("https://your-key@o0.ingest.sentry.io/0");
    options.setTracesSampleRate(1.0);
});
```

**3. That's it.** The library detects Sentry automatically. If Sentry is not on the classpath, tracing is a no-op with zero overhead.

## Requirements

- Java 8 or higher

## License

Apache License 2.0
