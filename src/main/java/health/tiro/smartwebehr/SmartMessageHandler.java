package health.tiro.smartwebehr;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import health.tiro.smartwebehr.events.*;
import health.tiro.smartwebehr.message.SmartMessageRequest;
import health.tiro.smartwebehr.message.SmartMessageResponse;
import health.tiro.smartwebehr.message.payload.*;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handler for SMART Web Messaging protocol.
 * Processes incoming messages from WebView and generates appropriate responses.
 */
public class SmartMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(SmartMessageHandler.class);
    private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("\"messageId\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /**
     * Shared FhirContext instance. FhirContext is thread-safe and expensive to create,
     * so it should be reused across all handler instances.
     */
    private static volatile FhirContext sharedFhirContext;

    private final Scratchpad scratchpad = new Scratchpad();
    private final ObjectMapper objectMapper;
    private final FhirContext fhirContext;
    private final IParser fhirJsonParser;
    private final List<SmartMessageListener> listeners = new ArrayList<>();
    private final Map<String, Consumer<SmartMessageResponse>> responseListeners = new ConcurrentHashMap<>();

    private MessageSender messageSender;

    /**
     * Functional interface for sending messages back to the WebView.
     */
    @FunctionalInterface
    public interface MessageSender {
        CompletableFuture<String> sendMessage(String jsonMessage);
    }

    /**
     * Returns the shared FhirContext instance, creating it lazily if needed.
     * Uses double-checked locking for thread-safe lazy initialization.
     */
    private static FhirContext getSharedFhirContext() {
        if (sharedFhirContext == null) {
            synchronized (SmartMessageHandler.class) {
                if (sharedFhirContext == null) {
                    sharedFhirContext = FhirContext.forR4();
                }
            }
        }
        return sharedFhirContext;
    }

    public SmartMessageHandler() {
        this(null, null);
    }

    public SmartMessageHandler(ObjectMapper customObjectMapper) {
        this(customObjectMapper, null);
    }

    /**
     * Creates a new SmartMessageHandler with optional custom ObjectMapper and FhirContext.
     *
     * @param customObjectMapper Custom ObjectMapper to use, or null for the default
     * @param customFhirContext  Custom FhirContext to use, or null to use the shared R4 context
     */
    public SmartMessageHandler(ObjectMapper customObjectMapper, FhirContext customFhirContext) {
        this.fhirContext = customFhirContext != null ? customFhirContext : getSharedFhirContext();
        this.fhirJsonParser = fhirContext.newJsonParser().setPrettyPrint(false);

        this.objectMapper = customObjectMapper != null ? customObjectMapper : createDefaultObjectMapper();

        logger.info("SmartMessageHandler initialized.");
    }

    private ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    public void setMessageSender(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    public void addListener(SmartMessageListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SmartMessageListener listener) {
        listeners.remove(listener);
    }

    /**
     * Handle an incoming JSON message from the WebView.
     * 
     * @param jsonMessage The raw JSON message string
     * @return The JSON response string, or null if no response is needed
     */
    public String handleMessage(String jsonMessage) {
        logger.debug("Received message for handling: {}", jsonMessage);
        
        try {
            // Determine message type by checking for responseToMessageId field
            if (jsonMessage.contains("\"responseToMessageId\"")) {
                logger.debug("Message identified as SmartMessageResponse.");
                SmartMessageResponse response = parseResponse(jsonMessage);
                handleResponseMessage(response);
                return null; // Response messages don't generate replies
            } else {
                logger.debug("Message identified as SmartMessageRequest.");
                SmartMessageRequest request = objectMapper.readValue(jsonMessage, SmartMessageRequest.class);
                logger.info("Handling message of type: {}", request.getMessageType());
                return handleRequestMessage(request);
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize message. JSON: {}", jsonMessage, e);
            String messageId = getMessageIdFromJson(jsonMessage);
            SmartMessageResponse response = SmartMessageResponse.createErrorResponse(messageId, new ErrorResponse(e));
            return serializeResponse(response);
        } catch (Exception e) {
            logger.error("An unhandled exception occurred during message handling. JSON: {}", jsonMessage, e);
            try {
                String messageId = getMessageIdFromJson(jsonMessage);
                SmartMessageResponse response = SmartMessageResponse.createErrorResponse(messageId, new ErrorResponse(e));
                return serializeResponse(response);
            } catch (Exception ex) {
                SmartMessageResponse response = SmartMessageResponse.createErrorResponse(null, new ErrorResponse(e));
                return serializeResponse(response);
            }
        }
    }

    private SmartMessageResponse parseResponse(String jsonMessage) throws JsonProcessingException {
        return objectMapper.readValue(jsonMessage, SmartMessageResponse.class);
    }

    private String handleRequestMessage(SmartMessageRequest message) {
        SmartMessageResponse response;
        
        try {
            String messageType = message.getMessageType();
            JsonNode payload = message.getPayload();
            
            switch (messageType) {
                case "scratchpad.create":
                    logger.debug("Handling scratchpad.create request.");
                    response = handleScratchpadCreate(message, parseResourcePayload(payload, "resource"));
                    break;
                    
                case "scratchpad.update":
                    logger.debug("Handling scratchpad.update request.");
                    response = handleScratchpadUpdate(message, parseResourcePayload(payload, "resource"));
                    break;
                    
                case "scratchpad.delete":
                    logger.debug("Handling scratchpad.delete request.");
                    response = handleScratchpadDelete(message, payload.get("location").asText());
                    break;
                    
                case "scratchpad.read":
                    logger.debug("Handling scratchpad.read request.");
                    String location = payload.has("location") && !payload.get("location").isNull() 
                        ? payload.get("location").asText() : null;
                    response = handleScratchpadRead(message, location);
                    break;
                    
                case "status.handshake":
                    logger.debug("Handling status.handshake request.");
                    response = handleHandshake(message);
                    break;
                    
                case "form.submitted":
                    logger.debug("Handling form.submitted request.");
                    response = handleFormSubmit(message, payload);
                    break;
                    
                case "ui.done":
                    logger.debug("Handling ui.done request.");
                    response = handleUiDone(message);
                    break;
                    
                default:
                    response = SmartMessageResponse.createErrorResponse(
                        message.getMessageId(), 
                        new ErrorResponse("Unknown messageType: " + messageType, "UnknownMessageTypeException")
                    );
                    break;
            }
        } catch (Exception e) {
            logger.error("Exception while handling request message: MessageId={}, MessageType={}", 
                message.getMessageId(), message.getMessageType(), e);
            response = SmartMessageResponse.createErrorResponse(message.getMessageId(), new ErrorResponse(e));
        }
        
        String responseJson = serializeResponse(response);
        logger.info("Created response={}", responseJson);
        return responseJson;
    }

    private void handleResponseMessage(SmartMessageResponse response) {
        logger.info("Handling response message for ResponseToMessageId: {}", response.getResponseToMessageId());
        
        Consumer<SmartMessageResponse> listener = responseListeners.get(response.getResponseToMessageId());
        if (listener != null) {
            logger.debug("Found listener for ResponseToMessageId: {}", response.getResponseToMessageId());
            try {
                listener.accept(response);
            } catch (Exception e) {
                logger.error("Exception occurred while executing response listener for ResponseToMessageId: {}", 
                    response.getResponseToMessageId(), e);
            } finally {
                if (!response.isAdditionalResponsesExpected()) {
                    responseListeners.remove(response.getResponseToMessageId());
                    logger.debug("Removed listener for ResponseToMessageId: {} as no additional responses expected.", 
                        response.getResponseToMessageId());
                }
            }
        } else {
            logger.warn("No listener found for response message with ResponseToMessageId: {}", 
                response.getResponseToMessageId());
        }
    }

    private Resource parseResourcePayload(JsonNode payload, String fieldName) {
        JsonNode resourceNode = payload.get(fieldName);
        if (resourceNode == null) {
            throw new IllegalArgumentException(fieldName + " is required in payload");
        }
        return (Resource) fhirJsonParser.parseResource(resourceNode.toString());
    }

    private SmartMessageResponse handleScratchpadCreate(SmartMessageRequest message, Resource resource) {
        String location = scratchpad.createResource(resource);
        
        // Fire ResourceChangedEvent
        ResourceChangedEvent event = new ResourceChangedEvent(this, resource);
        listeners.forEach(l -> l.onResourceChanged(event));
        logger.debug("ResourceChanged event invoked for MessageId: {}", message.getMessageId());
        
        return new SmartMessageResponse(
            UUID.randomUUID().toString(),
            message.getMessageId(),
            false,
            new ScratchpadCreateResponse("201 Created", location, null)
        );
    }

    private SmartMessageResponse handleScratchpadUpdate(SmartMessageRequest message, Resource resource) {
        scratchpad.updateResource(resource);
        
        // Fire ResourceChangedEvent
        ResourceChangedEvent event = new ResourceChangedEvent(this, resource);
        listeners.forEach(l -> l.onResourceChanged(event));
        logger.debug("ResourceChanged event invoked for MessageId: {}", message.getMessageId());
        
        return new SmartMessageResponse(
            UUID.randomUUID().toString(),
            message.getMessageId(),
            false,
            new ScratchpadUpdateResponse("200 OK", null)
        );
    }

    private SmartMessageResponse handleScratchpadDelete(SmartMessageRequest message, String location) {
        scratchpad.deleteResource(location);
        
        return new SmartMessageResponse(
            UUID.randomUUID().toString(),
            message.getMessageId(),
            false,
            new ScratchpadDeleteResponse("200 OK", null)
        );
    }

    private SmartMessageResponse handleScratchpadRead(SmartMessageRequest message, String location) {
        if (location == null) {
            List<Resource> resources = new ArrayList<>(scratchpad.getAllResources());
            return new SmartMessageResponse(
                UUID.randomUUID().toString(),
                message.getMessageId(),
                false,
                new ScratchpadReadResponse(null, resources, null)
            );
        } else {
            Resource resource = scratchpad.getResource(location);
            return new SmartMessageResponse(
                UUID.randomUUID().toString(),
                message.getMessageId(),
                false,
                new ScratchpadReadResponse(resource, null, null)
            );
        }
    }

    private SmartMessageResponse handleHandshake(SmartMessageRequest message) {
        logger.debug("Invoking HandshakeReceived event for MessageId: {}", message.getMessageId());
        
        HandshakeReceivedEvent event = new HandshakeReceivedEvent(this, message, message.getPayload());
        listeners.forEach(l -> l.onHandshakeReceived(event));
        logger.debug("HandshakeReceived event invoked for MessageId: {}", message.getMessageId());
        
        return new SmartMessageResponse(
            UUID.randomUUID().toString(),
            message.getMessageId(),
            false,
            new ResponsePayload()
        );
    }

    private SmartMessageResponse handleFormSubmit(SmartMessageRequest message, JsonNode payload) {
        logger.debug("Invoking FormSubmit for MessageId: {}", message.getMessageId());
        
        QuestionnaireResponse qr = (QuestionnaireResponse) parseResourcePayload(payload, "response");
        OperationOutcome outcome = payload.has("outcome") && !payload.get("outcome").isNull()
            ? (OperationOutcome) fhirJsonParser.parseResource(payload.get("outcome").toString())
            : null;
        
        FormSubmittedEvent event = new FormSubmittedEvent(this, qr, outcome);
        listeners.forEach(l -> l.onFormSubmitted(event));
        logger.debug("FormSubmit event invoked for MessageId: {}", message.getMessageId());
        
        return new SmartMessageResponse(
            UUID.randomUUID().toString(),
            message.getMessageId(),
            false,
            new ResponsePayload()
        );
    }

    private SmartMessageResponse handleUiDone(SmartMessageRequest message) {
        logger.debug("Invoking CloseApplication event for MessageId: {}", message.getMessageId());
        
        CloseApplicationEvent event = new CloseApplicationEvent(this);
        listeners.forEach(l -> l.onCloseApplication(event));
        logger.debug("CloseApplication event invoked for MessageId: {}", message.getMessageId());
        
        return new SmartMessageResponse(
            UUID.randomUUID().toString(),
            message.getMessageId(),
            false,
            new ResponsePayload()
        );
    }

    private String serializeResponse(SmartMessageResponse response) {
        try {
            // Build the response manually to handle FHIR resources properly
            ObjectNode node = objectMapper.createObjectNode();
            node.put("messageId", response.getMessageId());
            node.put("responseToMessageId", response.getResponseToMessageId());
            node.put("additionalResponsesExpected", response.isAdditionalResponsesExpected());
            
            ResponsePayload payload = response.getPayload();
            if (payload != null) {
                ObjectNode payloadNode = objectMapper.valueToTree(payload);
                
                // Handle FHIR resources in payload
                if (payload instanceof ScratchpadReadResponse) {
                    ScratchpadReadResponse readResponse = (ScratchpadReadResponse) payload;
                    if (readResponse.getResource() != null) {
                        String resourceJson = fhirJsonParser.encodeResourceToString(readResponse.getResource());
                        payloadNode.set("resource", objectMapper.readTree(resourceJson));
                    }
                    if (readResponse.getScratchpad() != null) {
                        ArrayNode scratchpadArray = objectMapper.createArrayNode();
                        for (Resource r : readResponse.getScratchpad()) {
                            String resourceJson = fhirJsonParser.encodeResourceToString(r);
                            scratchpadArray.add(objectMapper.readTree(resourceJson));
                        }
                        payloadNode.set("scratchpad", scratchpadArray);
                    }
                }
                
                node.set("payload", payloadNode);
            }
            
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize response", e);
            throw new RuntimeException("Failed to serialize response", e);
        }
    }

    public String getMessageIdFromJson(String json) {
        Matcher match = MESSAGE_ID_PATTERN.matcher(json);
        return match.find() ? match.group(1) : null;
    }

    // ========== Outbound message methods ==========

    public CompletableFuture<String> sendMessageAsync(String messageType, RequestPayload payload, Consumer<SmartMessageResponse> responseHandler) {
        logger.info("Sending message async: MessageType={}", messageType);
        
        if (messageSender == null) {
            throw new IllegalStateException("MessageSender must be set before sending messages");
        }
        
        String messageId = UUID.randomUUID().toString();
        SmartMessageRequest message = new SmartMessageRequest(
            messageId,
            "smart-web-messaging",
            messageType,
            payload != null ? objectMapper.valueToTree(payload) : objectMapper.createObjectNode()
        );
        
        if (responseHandler != null) {
            responseListeners.put(messageId, responseHandler);
            logger.debug("Registered response listener for MessageId: {}", messageId);
        }
        
        try {
            String requestJson = objectMapper.writeValueAsString(message);
            logger.debug("Sending JSON message: {}", requestJson);
            return messageSender.sendMessage(requestJson);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize request", e);
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    public CompletableFuture<String> sendFormRequestSubmitAsync(Consumer<SmartMessageResponse> responseHandler) {
        logger.debug("Sending ui.form.requestSubmit message.");
        return sendMessageAsync("ui.form.requestSubmit", new RequestPayload(), responseHandler);
    }

    public CompletableFuture<String> sendFormPersistAsync(Consumer<SmartMessageResponse> responseHandler) {
        logger.debug("Sending ui.form.persist message.");
        return sendMessageAsync("ui.form.persist", new RequestPayload(), responseHandler);
    }

    public CompletableFuture<String> sendSdcConfigureContextAsync(
            Reference subject,
            Reference author,
            Reference encounter,
            List<LaunchContext> launchContext,
            Consumer<SmartMessageResponse> responseHandler) {
        
        logger.debug("Sending sdc.configureContext message.");
        
        SdcConfigureContext payload = new SdcConfigureContext(subject, author, encounter, launchContext);
        return sendMessageAsync("sdc.configureContext", payload, responseHandler);
    }

    public CompletableFuture<String> sendSdcConfigureContextAsync(
            Patient patient,
            Encounter encounter,
            Practitioner author,
            Consumer<SmartMessageResponse> responseHandler) {
        
        logger.debug("Sending sdc.configureContext message with FHIR resources.");
        
        List<LaunchContext> launchContext = new ArrayList<>();
        if (patient != null) {
            launchContext.add(new LaunchContext("patient", null, patient));
        }
        if (encounter != null) {
            launchContext.add(new LaunchContext("encounter", null, encounter));
        }
        if (author != null) {
            launchContext.add(new LaunchContext("user", null, author));
        }
        
        return sendSdcConfigureContextAsync(null, null, null, launchContext, responseHandler);
    }

    public CompletableFuture<String> sendSdcConfigureAsync(
            String terminologyServer,
            String dataServer,
            Object configuration,
            Consumer<SmartMessageResponse> responseHandler) {
        
        logger.debug("Sending sdc.configure message.");
        
        SdcConfigure payload = new SdcConfigure(terminologyServer, dataServer, configuration);
        return sendMessageAsync("sdc.configure", payload, responseHandler);
    }

    public CompletableFuture<String> sendSdcDisplayQuestionnaireAsync(
            Object questionnaire,
            QuestionnaireResponse questionnaireResponse,
            Reference subject,
            Reference author,
            Reference encounter,
            List<LaunchContext> launchContext,
            Consumer<SmartMessageResponse> responseHandler) {
        
        logger.debug("Sending sdc.displayQuestionnaire message.");
        
        SdcDisplayQuestionnaire.SdcDisplayQuestionnaireContext context = 
            new SdcDisplayQuestionnaire.SdcDisplayQuestionnaireContext(subject, author, encounter, launchContext);
        
        SdcDisplayQuestionnaire payload = new SdcDisplayQuestionnaire(questionnaire, questionnaireResponse, context);
        return sendMessageAsync("sdc.displayQuestionnaire", payload, responseHandler);
    }

    public CompletableFuture<String> sendSdcDisplayQuestionnaireAsync(
            Questionnaire questionnaire,
            QuestionnaireResponse questionnaireResponse,
            Patient patient,
            Encounter encounter,
            Practitioner author,
            Consumer<SmartMessageResponse> responseHandler) {
        
        logger.debug("Sending sdc.displayQuestionnaire message with FHIR resources.");
        
        List<LaunchContext> launchContext = new ArrayList<>();
        if (patient != null) {
            launchContext.add(new LaunchContext("patient", null, patient));
        }
        if (encounter != null) {
            launchContext.add(new LaunchContext("encounter", null, encounter));
        }
        if (author != null) {
            launchContext.add(new LaunchContext("user", null, author));
        }
        
        return sendSdcDisplayQuestionnaireAsync(questionnaire, questionnaireResponse, null, null, null, launchContext, responseHandler);
    }

    public CompletableFuture<String> sendSdcDisplayQuestionnaireAsync(
            String questionnaireCanonicalUrl,
            QuestionnaireResponse questionnaireResponse,
            Patient patient,
            Encounter encounter,
            Practitioner author,
            Consumer<SmartMessageResponse> responseHandler) {
        
        logger.debug("Sending sdc.displayQuestionnaire message with canonical URL.");
        
        List<LaunchContext> launchContext = new ArrayList<>();
        if (patient != null) {
            launchContext.add(new LaunchContext("patient", null, patient));
        }
        if (encounter != null) {
            launchContext.add(new LaunchContext("encounter", null, encounter));
        }
        if (author != null) {
            launchContext.add(new LaunchContext("user", null, author));
        }
        
        return sendSdcDisplayQuestionnaireAsync(questionnaireCanonicalUrl, questionnaireResponse, null, null, null, launchContext, responseHandler);
    }

    // ========== Response listener management ==========

    public void registerResponseListener(String messageId, Consumer<SmartMessageResponse> responseHandler) {
        logger.debug("Registering response listener for MessageId: {}", messageId);
        responseListeners.put(messageId, responseHandler);
    }

    public void unregisterResponseListener(String messageId) {
        if (responseListeners.remove(messageId) != null) {
            logger.debug("Unregistered response listener for MessageId: {}", messageId);
        } else {
            logger.warn("Attempted to unregister non-existent listener for MessageId: {}", messageId);
        }
    }

    public boolean hasPendingResponseListener(String messageId) {
        boolean hasListener = responseListeners.containsKey(messageId);
        logger.debug("Checking for pending response listener for MessageId: {}. Result: {}", messageId, hasListener);
        return hasListener;
    }

    public void clearAllResponseListeners() {
        responseListeners.clear();
        logger.debug("All response listeners cleared.");
    }

    // ========== Getters ==========

    public Scratchpad getScratchpad() {
        return scratchpad;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public FhirContext getFhirContext() {
        return fhirContext;
    }
}
