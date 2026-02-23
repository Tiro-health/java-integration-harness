# SMART Web EHR - Java

A Java port of the [SMARTWebEHR](https://github.com/Tiro-health/SMARTWebEHR) .NET library for handling SMART Web Messaging protocol in embedded browsers.

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

// 3. Create the viewer
FormFillerConfig config = FormFillerConfig.builder()
    .targetUrl("https://your-form-app.com/form-filler.html")
    .build();
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

## Examples

See the [`examples/`](examples/) directory for runnable demo applications:

- **[JxBrowser — minimal](examples/jxbrowser/src/main/java/health/tiro/examples/jxbrowser/Main.java)** — basic form filler with JxBrowser (FHIR R4)
- **[JxBrowser — complete](examples/jxbrowser/src/main/java/health/tiro/examples/jxbrowser/CompleteExample.java)** — EHR-style UI with patient context, template switching, and saved progress
- **[Equo Chromium](examples/equo/src/main/java/health/tiro/examples/equo/Main.java)** — basic form filler with Equo Chromium (FHIR R5)

**Running the examples:**

First, serve the form filler page on `http://localhost:8000`:

```bash
cd examples/src/main/resources/form-filler
python3 -m http.server 8000
```

Then in a separate terminal:

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

## Requirements

- Java 8 or higher

## License

MIT License - same as the original .NET library.
