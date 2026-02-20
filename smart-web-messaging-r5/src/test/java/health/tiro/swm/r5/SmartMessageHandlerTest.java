package health.tiro.swm.r5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.tiro.swm.events.CloseApplicationEvent;
import health.tiro.swm.events.FormSubmittedEvent;
import health.tiro.swm.events.HandshakeReceivedEvent;
import health.tiro.swm.events.SmartMessageListener;
import health.tiro.swm.message.SmartMessageResponse;
import health.tiro.swm.message.payload.LaunchContext;
import org.hl7.fhir.r5.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SmartMessageHandlerTest {

    private SmartMessageHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        handler = new SmartMessageHandler();
        objectMapper = new ObjectMapper();
    }

    @Test
    void handleHandshakeRequest() throws Exception {
        String request = "{"
                + "\"messageId\": \"msg-123\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"status.handshake\","
                + "\"payload\": {}"
                + "}";

        AtomicReference<HandshakeReceivedEvent> receivedEvent = new AtomicReference<>();
        handler.addListener(new SmartMessageListener() {
            @Override
            public void onHandshakeReceived(HandshakeReceivedEvent event) {
                receivedEvent.set(event);
            }
        });

        String response = handler.handleMessage(request);

        assertNotNull(response);
        JsonNode responseNode = objectMapper.readTree(response);
        assertEquals("msg-123", responseNode.get("responseToMessageId").asText());
        assertFalse(responseNode.get("additionalResponsesExpected").asBoolean());
        assertNotNull(receivedEvent.get());
    }

    @Test
    void handleFormSubmittedRequest() throws Exception {
        String request = "{"
                + "\"messageId\": \"msg-form\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"form.submitted\","
                + "\"payload\": {"
                + "  \"response\": {"
                + "    \"resourceType\": \"QuestionnaireResponse\","
                + "    \"status\": \"completed\","
                + "    \"questionnaire\": \"http://example.org/Questionnaire/test\""
                + "  }"
                + "}"
                + "}";

        AtomicReference<FormSubmittedEvent> receivedEvent = new AtomicReference<>();
        handler.addListener(new SmartMessageListener() {
            @Override
            public void onFormSubmitted(FormSubmittedEvent event) {
                receivedEvent.set(event);
            }
        });

        String response = handler.handleMessage(request);

        assertNotNull(response);
        JsonNode responseNode = objectMapper.readTree(response);
        assertEquals("msg-form", responseNode.get("responseToMessageId").asText());

        assertNotNull(receivedEvent.get());
        QuestionnaireResponse qr = (QuestionnaireResponse) receivedEvent.get().getResponse();
        assertNotNull(qr);
        assertEquals(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED, qr.getStatus());
    }

    @Test
    void handleUiDoneRequest() throws Exception {
        String request = "{"
                + "\"messageId\": \"msg-done\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"ui.done\","
                + "\"payload\": {}"
                + "}";

        AtomicReference<CloseApplicationEvent> receivedEvent = new AtomicReference<>();
        handler.addListener(new SmartMessageListener() {
            @Override
            public void onCloseApplication(CloseApplicationEvent event) {
                receivedEvent.set(event);
            }
        });

        String response = handler.handleMessage(request);

        assertNotNull(response);
        JsonNode responseNode = objectMapper.readTree(response);
        assertEquals("msg-done", responseNode.get("responseToMessageId").asText());
        assertNotNull(receivedEvent.get());
    }

    @Test
    void handleUnknownMessageType() throws Exception {
        String request = "{"
                + "\"messageId\": \"msg-unknown\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"unknown.type\","
                + "\"payload\": {}"
                + "}";

        String response = handler.handleMessage(request);

        assertNotNull(response);
        JsonNode responseNode = objectMapper.readTree(response);
        assertEquals("msg-unknown", responseNode.get("responseToMessageId").asText());
        assertNotNull(responseNode.get("payload").get("errorMessage"));
        assertTrue(responseNode.get("payload").get("errorMessage").asText().contains("Unknown messageType"));
    }

    @Test
    void handleMalformedJson() {
        String malformedJson = "{ invalid json }";

        String response = handler.handleMessage(malformedJson);

        assertNotNull(response);
        assertTrue(response.contains("errorMessage"));
    }

    @Test
    void getMessageIdFromJson() {
        String json = "{\"messageId\": \"test-id-123\", \"other\": \"value\"}";

        String messageId = handler.getMessageIdFromJson(json);

        assertEquals("test-id-123", messageId);
    }

    @Test
    void getMessageIdFromJsonReturnsNullWhenMissing() {
        String json = "{\"other\": \"value\"}";

        String messageId = handler.getMessageIdFromJson(json);

        assertNull(messageId);
    }

    // ========== sendSdcDisplayQuestionnaireAsync tests ==========

    @Test
    void sendSdcDisplayQuestionnaireAsync_withQuestionnaireResource() throws Exception {
        AtomicReference<String> sentMessage = new AtomicReference<>();
        handler.setMessageSender(msg -> {
            sentMessage.set(msg);
            return CompletableFuture.completedFuture("OK");
        });

        Questionnaire questionnaire = new Questionnaire();
        questionnaire.setId("test-questionnaire");
        questionnaire.setStatus(Enumerations.PublicationStatus.ACTIVE);

        Patient patient = new Patient();
        patient.setId("patient-123");

        Encounter encounter = new Encounter();
        encounter.setId("encounter-456");

        Practitioner author = new Practitioner();
        author.setId("practitioner-789");

        AtomicReference<SmartMessageResponse> receivedResponse = new AtomicReference<>();
        handler.sendSdcDisplayQuestionnaireAsync(
                questionnaire,
                null,
                patient,
                encounter,
                author,
                receivedResponse::set
        );

        assertNotNull(sentMessage.get());
        JsonNode messageNode = objectMapper.readTree(sentMessage.get());

        assertEquals("sdc.displayQuestionnaire", messageNode.get("messageType").asText());
        assertEquals("smart-web-messaging", messageNode.get("messagingHandle").asText());
        assertNotNull(messageNode.get("messageId").asText());

        JsonNode payload = messageNode.get("payload");
        assertNotNull(payload.get("questionnaire"));
        assertNotNull(payload.get("context"));
        assertNotNull(payload.get("context").get("launchContext"));
        assertTrue(payload.get("context").get("launchContext").isArray());
        assertEquals(3, payload.get("context").get("launchContext").size());
    }

    @Test
    void sendSdcDisplayQuestionnaireAsync_withCanonicalUrl() throws Exception {
        AtomicReference<String> sentMessage = new AtomicReference<>();
        handler.setMessageSender(msg -> {
            sentMessage.set(msg);
            return CompletableFuture.completedFuture("OK");
        });

        String canonicalUrl = "http://example.org/Questionnaire/test";

        handler.sendSdcDisplayQuestionnaireAsync(
                canonicalUrl,
                null,
                null,
                null,
                null,
                null
        );

        assertNotNull(sentMessage.get());
        JsonNode messageNode = objectMapper.readTree(sentMessage.get());

        assertEquals("sdc.displayQuestionnaire", messageNode.get("messageType").asText());

        JsonNode payload = messageNode.get("payload");
        assertEquals(canonicalUrl, payload.get("questionnaire").asText());
    }

    @Test
    void sendSdcDisplayQuestionnaireAsync_withQuestionnaireResponse() throws Exception {
        AtomicReference<String> sentMessage = new AtomicReference<>();
        handler.setMessageSender(msg -> {
            sentMessage.set(msg);
            return CompletableFuture.completedFuture("OK");
        });

        Questionnaire questionnaire = new Questionnaire();
        questionnaire.setId("test-questionnaire");

        QuestionnaireResponse qr = new QuestionnaireResponse();
        qr.setId("qr-123");
        qr.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS);

        handler.sendSdcDisplayQuestionnaireAsync(
                questionnaire,
                qr,
                null,
                null,
                null,
                null
        );

        assertNotNull(sentMessage.get());
        JsonNode messageNode = objectMapper.readTree(sentMessage.get());
        JsonNode payload = messageNode.get("payload");

        assertNotNull(payload.get("questionnaireResponse"));
    }

    @Test
    void sendSdcDisplayQuestionnaireAsync_withReferences() throws Exception {
        AtomicReference<String> sentMessage = new AtomicReference<>();
        handler.setMessageSender(msg -> {
            sentMessage.set(msg);
            return CompletableFuture.completedFuture("OK");
        });

        Reference subject = new Reference("Patient/123");
        Reference author = new Reference("Practitioner/456");
        Reference encounter = new Reference("Encounter/789");

        List<LaunchContext> launchContext = new ArrayList<>();
        launchContext.add(new LaunchContext("custom", new Reference("Organization/org-1"), null));

        Questionnaire questionnaire = new Questionnaire();
        questionnaire.setId("test-questionnaire");

        handler.sendSdcDisplayQuestionnaireAsync(
                questionnaire,
                null,
                subject,
                author,
                encounter,
                launchContext,
                null
        );

        assertNotNull(sentMessage.get());
        JsonNode messageNode = objectMapper.readTree(sentMessage.get());
        JsonNode context = messageNode.get("payload").get("context");

        assertNotNull(context.get("subject"));
        assertNotNull(context.get("author"));
        assertNotNull(context.get("encounter"));
        assertEquals(1, context.get("launchContext").size());
        assertEquals("custom", context.get("launchContext").get(0).get("name").asText());
    }

    @Test
    void sendSdcDisplayQuestionnaireAsync_registersResponseListener() throws Exception {
        AtomicReference<String> sentMessage = new AtomicReference<>();
        handler.setMessageSender(msg -> {
            sentMessage.set(msg);
            return CompletableFuture.completedFuture("OK");
        });

        AtomicReference<SmartMessageResponse> receivedResponse = new AtomicReference<>();
        handler.sendSdcDisplayQuestionnaireAsync(
                "http://example.org/Questionnaire/test",
                null,
                null,
                null,
                null,
                receivedResponse::set
        );

        // Extract messageId from the sent message
        String messageId = objectMapper.readTree(sentMessage.get()).get("messageId").asText();

        // Verify listener was registered
        assertTrue(handler.hasPendingResponseListener(messageId));
    }

    @Test
    void sendSdcDisplayQuestionnaireAsync_responseListenerCanBeCleared() throws Exception {
        AtomicReference<String> capturedMessageId = new AtomicReference<>();
        handler.setMessageSender(msg -> {
            try {
                capturedMessageId.set(objectMapper.readTree(msg).get("messageId").asText());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return CompletableFuture.completedFuture("OK");
        });

        AtomicReference<SmartMessageResponse> receivedResponse = new AtomicReference<>();
        handler.sendSdcDisplayQuestionnaireAsync(
                "http://example.org/Questionnaire/test",
                null,
                null,
                null,
                null,
                receivedResponse::set
        );

        // Verify listener is registered
        assertTrue(handler.hasPendingResponseListener(capturedMessageId.get()));

        // Verify we can unregister the listener
        handler.unregisterResponseListener(capturedMessageId.get());
        assertFalse(handler.hasPendingResponseListener(capturedMessageId.get()));
    }

    @Test
    void sendSdcDisplayQuestionnaireAsync_clearAllListeners() throws Exception {
        AtomicReference<String> capturedMessageId = new AtomicReference<>();
        handler.setMessageSender(msg -> {
            try {
                capturedMessageId.set(objectMapper.readTree(msg).get("messageId").asText());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return CompletableFuture.completedFuture("OK");
        });

        handler.sendSdcDisplayQuestionnaireAsync(
                "http://example.org/Questionnaire/test",
                null,
                null,
                null,
                null,
                response -> {}
        );

        assertTrue(handler.hasPendingResponseListener(capturedMessageId.get()));

        handler.clearAllResponseListeners();

        assertFalse(handler.hasPendingResponseListener(capturedMessageId.get()));
    }

    @Test
    void sendSdcDisplayQuestionnaireAsync_throwsWithoutMessageSender() {
        Questionnaire questionnaire = new Questionnaire();
        questionnaire.setId("test-questionnaire");

        assertThrows(IllegalStateException.class, () -> {
            handler.sendSdcDisplayQuestionnaireAsync(
                    questionnaire,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        });
    }

    @Test
    void sendSdcDisplayQuestionnaireAsync_withNullResponseHandler() throws Exception {
        AtomicReference<String> sentMessage = new AtomicReference<>();
        handler.setMessageSender(msg -> {
            sentMessage.set(msg);
            return CompletableFuture.completedFuture("OK");
        });

        handler.sendSdcDisplayQuestionnaireAsync(
                "http://example.org/Questionnaire/test",
                null,
                null,
                null,
                null,
                null
        );

        assertNotNull(sentMessage.get());
        JsonNode messageNode = objectMapper.readTree(sentMessage.get());
        String messageId = messageNode.get("messageId").asText();

        // No listener should be registered when responseHandler is null
        assertFalse(handler.hasPendingResponseListener(messageId));
    }

    @Test
    void sendSdcDisplayQuestionnaireAsync_launchContextContainsPatientEncounterAuthor() throws Exception {
        AtomicReference<String> sentMessage = new AtomicReference<>();
        handler.setMessageSender(msg -> {
            sentMessage.set(msg);
            return CompletableFuture.completedFuture("OK");
        });

        Patient patient = new Patient();
        patient.setId("patient-123");
        patient.addName().setFamily("Doe").addGiven("John");

        Encounter encounter = new Encounter();
        encounter.setId("encounter-456");

        Practitioner author = new Practitioner();
        author.setId("practitioner-789");

        handler.sendSdcDisplayQuestionnaireAsync(
                new Questionnaire(),
                null,
                patient,
                encounter,
                author,
                null
        );

        JsonNode messageNode = objectMapper.readTree(sentMessage.get());
        JsonNode launchContext = messageNode.get("payload").get("context").get("launchContext");

        // Verify launch context entries
        List<String> names = new ArrayList<>();
        for (JsonNode ctx : launchContext) {
            names.add(ctx.get("name").asText());
        }

        assertTrue(names.contains("patient"));
        assertTrue(names.contains("encounter"));
        assertTrue(names.contains("user"));
    }

    @Test
    void sendSdcDisplayQuestionnaireAsync_partialContext() throws Exception {
        AtomicReference<String> sentMessage = new AtomicReference<>();
        handler.setMessageSender(msg -> {
            sentMessage.set(msg);
            return CompletableFuture.completedFuture("OK");
        });

        Patient patient = new Patient();
        patient.setId("patient-123");

        // Only patient, no encounter or author
        handler.sendSdcDisplayQuestionnaireAsync(
                new Questionnaire(),
                null,
                patient,
                null,
                null,
                null
        );

        JsonNode messageNode = objectMapper.readTree(sentMessage.get());
        JsonNode launchContext = messageNode.get("payload").get("context").get("launchContext");

        assertEquals(1, launchContext.size());
        assertEquals("patient", launchContext.get(0).get("name").asText());
    }

}
