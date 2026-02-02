# SMART Web EHR - Java

A Java port of the [SMARTWebEHR](https://github.com/Tiro-health/SMARTWebEHR) .NET library for handling SMART Web Messaging protocol in embedded browsers.

## Features

- Parse and handle SMART Web Messaging messages
- Scratchpad operations (create, read, update, delete)
- SDC (Structured Data Capture) operations
- Form submission handling
- Event-driven architecture
- HAPI FHIR R4 integration

## Installation

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>health.tiro</groupId>
    <artifactId>smart-web-ehr</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Usage

### Basic Setup

```java
import health.tiro.smartwebehr.SmartMessageHandler;
import health.tiro.smartwebehr.events.*;

// Create handler
SmartMessageHandler handler = new SmartMessageHandler();

// Add event listeners
handler.addListener(new SmartMessageListener() {
    @Override
    public void onFormSubmitted(FormSubmittedEvent event) {
        QuestionnaireResponse response = event.getResponse();
        OperationOutcome outcome = event.getOutcome();
        // Process the submitted form
    }

    @Override
    public void onResourceChanged(ResourceChangedEvent event) {
        Resource resource = event.getResource();
        // Handle resource changes
    }

    @Override
    public void onCloseApplication(CloseApplicationEvent event) {
        // Close the form viewer
    }
});
```

### Integration with JxBrowser

```java
import com.teamdev.jxbrowser.browser.Browser;
import com.teamdev.jxbrowser.engine.Engine;
import com.teamdev.jxbrowser.js.JsObject;

public class JxBrowserIntegration {
    
    private SmartMessageHandler handler;
    private Browser browser;
    
    public void setup() {
        handler = new SmartMessageHandler();
        
        // Set up message sender for outbound messages
        handler.setMessageSender(jsonMessage -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            browser.mainFrame().ifPresent(frame -> {
                frame.executeJavaScript("window.postMessage(" + jsonMessage + ", '*')");
                future.complete(null);
            });
            return future;
        });
        
        // Inject message handler into browser
        browser.mainFrame().ifPresent(frame -> {
            JsObject window = frame.executeJavaScript("window");
            window.putProperty("smartMessageCallback", (String message) -> {
                String response = handler.handleMessage(message);
                if (response != null) {
                    frame.executeJavaScript("window.postMessage(" + response + ", '*')");
                }
            });
        });
        
        // Add listener for incoming messages
        browser.mainFrame().ifPresent(frame -> {
            frame.executeJavaScript(
                "window.addEventListener('message', function(event) {" +
                "  if (event.data && event.data.messageId) {" +
                "    window.smartMessageCallback(JSON.stringify(event.data));" +
                "  }" +
                "});"
            );
        });
    }
}
```

### Integration with Equo Chromium

```java
import com.nicepay.nicefx.browser.ChromiumBrowser;

public class EquoIntegration {
    
    private SmartMessageHandler handler;
    private ChromiumBrowser browser;
    
    public void setup() {
        handler = new SmartMessageHandler();
        
        // Set up message sender
        handler.setMessageSender(jsonMessage -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            browser.executeJavaScript("window.postMessage(" + jsonMessage + ", '*')");
            future.complete(null);
            return future;
        });
        
        // Register Java function to be called from JavaScript
        browser.registerBrowserFunction("smartMessageCallback", args -> {
            if (args.length > 0) {
                String message = args[0].toString();
                String response = handler.handleMessage(message);
                if (response != null) {
                    browser.executeJavaScript("window.postMessage(" + response + ", '*')");
                }
            }
            return null;
        });
        
        // Set up message listener in browser
        browser.executeJavaScript(
            "window.addEventListener('message', function(event) {" +
            "  if (event.data && event.data.messageId) {" +
            "    smartMessageCallback(JSON.stringify(event.data));" +
            "  }" +
            "});"
        );
    }
}
```

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

### Accessing the Scratchpad

```java
Scratchpad scratchpad = handler.getScratchpad();

// Get all resources
Collection<Resource> resources = scratchpad.getAllResources();

// Get specific resource
Resource resource = scratchpad.getResource("Patient/123");

// Clear scratchpad
scratchpad.clear();
```

## Message Types Supported

### Inbound (from WebView)
- `scratchpad.create` - Create a resource in the scratchpad
- `scratchpad.read` - Read resource(s) from the scratchpad
- `scratchpad.update` - Update a resource in the scratchpad
- `scratchpad.delete` - Delete a resource from the scratchpad
- `status.handshake` - Handshake from embedded app
- `form.submitted` - Form submission with QuestionnaireResponse
- `ui.done` - Application close request

### Outbound (to WebView)
- `ui.form.requestSubmit` - Request form submission
- `ui.form.persist` - Request form persistence
- `sdc.configure` - Configure SDC settings
- `sdc.configureContext` - Configure launch context
- `sdc.displayQuestionnaire` - Display a questionnaire

## Requirements

- Java 8 or higher

## Dependencies

- HAPI FHIR R4 (5.7.0)
- Jackson Databind (2.15.3)
- SLF4J (2.0.9)
- Javax Validation API (2.0.1.Final)

## License

MIT License - same as the original .NET library.
