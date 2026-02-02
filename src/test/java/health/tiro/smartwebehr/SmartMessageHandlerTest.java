package health.tiro.smartwebehr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.tiro.smartwebehr.events.CloseApplicationEvent;
import health.tiro.smartwebehr.events.FormSubmittedEvent;
import health.tiro.smartwebehr.events.HandshakeReceivedEvent;
import health.tiro.smartwebehr.events.ResourceChangedEvent;
import health.tiro.smartwebehr.events.SmartMessageListener;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SmartMessageHandlerTest {

    private SmartMessageHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        handler = new SmartMessageHandler();
        objectMapper = handler.getObjectMapper();
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
    void handleScratchpadCreateRequest() throws Exception {
        String request = "{"
                + "\"messageId\": \"msg-456\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.create\","
                + "\"payload\": {"
                + "  \"resource\": {"
                + "    \"resourceType\": \"Observation\","
                + "    \"status\": \"final\","
                + "    \"code\": {"
                + "      \"coding\": [{"
                + "        \"system\": \"http://loinc.org\","
                + "        \"code\": \"8867-4\","
                + "        \"display\": \"Heart rate\""
                + "      }]"
                + "    }"
                + "  }"
                + "}"
                + "}";

        List<ResourceChangedEvent> events = new ArrayList<>();
        handler.addListener(new SmartMessageListener() {
            @Override
            public void onResourceChanged(ResourceChangedEvent event) {
                events.add(event);
            }
        });

        String response = handler.handleMessage(request);

        assertNotNull(response);
        JsonNode responseNode = objectMapper.readTree(response);
        assertEquals("msg-456", responseNode.get("responseToMessageId").asText());
        assertEquals("201 Created", responseNode.get("payload").get("status").asText());
        assertNotNull(responseNode.get("payload").get("location").asText());
        assertTrue(responseNode.get("payload").get("location").asText().startsWith("Observation/"));

        assertEquals(1, events.size());
        assertTrue(events.get(0).getResource() instanceof Observation);

        assertEquals(1, handler.getScratchpad().size());
    }

    @Test
    void handleScratchpadUpdateRequest() throws Exception {
        // First create a resource
        String createRequest = "{"
                + "\"messageId\": \"msg-create\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.create\","
                + "\"payload\": {"
                + "  \"resource\": {"
                + "    \"resourceType\": \"Observation\","
                + "    \"status\": \"preliminary\","
                + "    \"code\": {\"coding\": [{\"system\": \"http://loinc.org\", \"code\": \"8867-4\"}]}"
                + "  }"
                + "}"
                + "}";
        String createResponse = handler.handleMessage(createRequest);
        JsonNode createResponseNode = objectMapper.readTree(createResponse);
        String location = createResponseNode.get("payload").get("location").asText();
        String id = location.substring(location.lastIndexOf('/') + 1);

        // Now update it using the ID from create response
        String updateRequest = "{"
                + "\"messageId\": \"msg-update\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.update\","
                + "\"payload\": {"
                + "  \"resource\": {"
                + "    \"resourceType\": \"Observation\","
                + "    \"id\": \"" + id + "\","
                + "    \"status\": \"final\","
                + "    \"code\": {\"coding\": [{\"system\": \"http://loinc.org\", \"code\": \"8867-4\"}]}"
                + "  }"
                + "}"
                + "}";

        List<ResourceChangedEvent> events = new ArrayList<>();
        handler.addListener(new SmartMessageListener() {
            @Override
            public void onResourceChanged(ResourceChangedEvent event) {
                events.add(event);
            }
        });

        String response = handler.handleMessage(updateRequest);

        assertNotNull(response);
        JsonNode responseNode = objectMapper.readTree(response);
        assertEquals("msg-update", responseNode.get("responseToMessageId").asText());
        assertEquals("200 OK", responseNode.get("payload").get("status").asText());

        assertEquals(1, events.size());
        Observation updated = (Observation) events.get(0).getResource();
        assertEquals(Observation.ObservationStatus.FINAL, updated.getStatus());
    }

    @Test
    void handleScratchpadDeleteRequest() throws Exception {
        // First create a resource
        String createRequest = "{"
                + "\"messageId\": \"msg-create\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.create\","
                + "\"payload\": {"
                + "  \"resource\": {"
                + "    \"resourceType\": \"Observation\","
                + "    \"status\": \"final\","
                + "    \"code\": {\"coding\": [{\"system\": \"http://loinc.org\", \"code\": \"8867-4\"}]}"
                + "  }"
                + "}"
                + "}";
        String createResponse = handler.handleMessage(createRequest);
        JsonNode createResponseNode = objectMapper.readTree(createResponse);
        String location = createResponseNode.get("payload").get("location").asText();
        assertEquals(1, handler.getScratchpad().size());

        // Now delete it using the location from create response
        String deleteRequest = "{"
                + "\"messageId\": \"msg-delete\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.delete\","
                + "\"payload\": {"
                + "  \"location\": \"" + location + "\""
                + "}"
                + "}";

        String response = handler.handleMessage(deleteRequest);

        assertNotNull(response);
        JsonNode responseNode = objectMapper.readTree(response);
        assertEquals("msg-delete", responseNode.get("responseToMessageId").asText());
        assertEquals("200 OK", responseNode.get("payload").get("status").asText());

        assertEquals(0, handler.getScratchpad().size());
    }

    @Test
    void handleScratchpadReadSingleResource() throws Exception {
        // First create a resource
        String createRequest = "{"
                + "\"messageId\": \"msg-create\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.create\","
                + "\"payload\": {"
                + "  \"resource\": {"
                + "    \"resourceType\": \"Observation\","
                + "    \"status\": \"final\","
                + "    \"code\": {\"coding\": [{\"system\": \"http://loinc.org\", \"code\": \"8867-4\"}]}"
                + "  }"
                + "}"
                + "}";
        String createResponse = handler.handleMessage(createRequest);
        JsonNode createResponseNode = objectMapper.readTree(createResponse);
        String location = createResponseNode.get("payload").get("location").asText();

        // Now read it using the location from create response
        String readRequest = "{"
                + "\"messageId\": \"msg-read\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.read\","
                + "\"payload\": {"
                + "  \"location\": \"" + location + "\""
                + "}"
                + "}";

        String response = handler.handleMessage(readRequest);

        assertNotNull(response);
        JsonNode responseNode = objectMapper.readTree(response);
        assertEquals("msg-read", responseNode.get("responseToMessageId").asText());
        assertNotNull(responseNode.get("payload").get("resource"));
        assertEquals("Observation", responseNode.get("payload").get("resource").get("resourceType").asText());
    }

    @Test
    void handleScratchpadReadAllResources() throws Exception {
        // Create two resources (without IDs, let the scratchpad assign them)
        String createRequest1 = "{"
                + "\"messageId\": \"msg-create-1\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.create\","
                + "\"payload\": {"
                + "  \"resource\": {"
                + "    \"resourceType\": \"Observation\","
                + "    \"status\": \"final\","
                + "    \"code\": {\"coding\": [{\"system\": \"http://loinc.org\", \"code\": \"8867-4\"}]}"
                + "  }"
                + "}"
                + "}";
        String createRequest2 = "{"
                + "\"messageId\": \"msg-create-2\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.create\","
                + "\"payload\": {"
                + "  \"resource\": {"
                + "    \"resourceType\": \"Observation\","
                + "    \"status\": \"preliminary\","
                + "    \"code\": {\"coding\": [{\"system\": \"http://loinc.org\", \"code\": \"8310-5\"}]}"
                + "  }"
                + "}"
                + "}";
        handler.handleMessage(createRequest1);
        handler.handleMessage(createRequest2);

        // Read all (no location)
        String readRequest = "{"
                + "\"messageId\": \"msg-read-all\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.read\","
                + "\"payload\": {"
                + "  \"location\": null"
                + "}"
                + "}";

        String response = handler.handleMessage(readRequest);

        assertNotNull(response);
        JsonNode responseNode = objectMapper.readTree(response);
        assertEquals("msg-read-all", responseNode.get("responseToMessageId").asText());
        assertNotNull(responseNode.get("payload").get("scratchpad"));
        assertTrue(responseNode.get("payload").get("scratchpad").isArray());
        assertEquals(2, responseNode.get("payload").get("scratchpad").size());
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
        QuestionnaireResponse qr = receivedEvent.get().getResponse();
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
    void handleMissingResourceInPayload() throws Exception {
        String request = "{"
                + "\"messageId\": \"msg-missing\","
                + "\"messagingHandle\": \"smart-web-messaging\","
                + "\"messageType\": \"scratchpad.create\","
                + "\"payload\": {}"
                + "}";

        String response = handler.handleMessage(request);

        assertNotNull(response);
        JsonNode responseNode = objectMapper.readTree(response);
        assertEquals("msg-missing", responseNode.get("responseToMessageId").asText());
        assertNotNull(responseNode.get("payload").get("errorMessage"));
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

}