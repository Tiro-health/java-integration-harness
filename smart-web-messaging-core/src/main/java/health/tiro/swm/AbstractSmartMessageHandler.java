package health.tiro.swm;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import health.tiro.swm.events.*;
import health.tiro.swm.message.SmartMessageRequest;
import health.tiro.swm.message.SmartMessageResponse;
import health.tiro.swm.message.payload.*;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract handler for SMART Web Messaging protocol.
 * Contains all version-independent logic. Subclasses provide FHIR-version-specific behavior.
 */
public abstract class AbstractSmartMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(AbstractSmartMessageHandler.class);
    private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("\"messageId\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final IParser fhirJsonParser;
    private final List<SmartMessageListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Consumer<SmartMessageResponse>> responseListeners = new ConcurrentHashMap<>();

    private volatile MessageSender messageSender;

    /**
     * Functional interface for sending messages back to the WebView.
     */
    @FunctionalInterface
    public interface MessageSender {
        CompletableFuture<String> sendMessage(String jsonMessage);
    }

    /**
     * Creates a new handler with the given FhirContext and optional custom ObjectMapper.
     *
     * @param fhirContext         The FhirContext for the target FHIR version
     * @param customObjectMapper  Custom ObjectMapper to use, or null for the default
     */
    protected AbstractSmartMessageHandler(FhirContext fhirContext, ObjectMapper customObjectMapper) {
        this.fhirJsonParser = fhirContext.newJsonParser()
                .setPrettyPrint(false)
                .setParserErrorHandler(new ca.uhn.fhir.parser.LenientErrorHandler().setErrorOnInvalidValue(false));
        this.objectMapper = customObjectMapper != null ? customObjectMapper : createDefaultObjectMapper();
        logger.info("SmartMessageHandler initialized.");
    }

    /**
     * Serialize a version-specific Reference to a JSON ObjectNode.
     * Must be implemented by version-specific subclasses because
     * IBaseReference lacks getReference(), getDisplay(), getType() methods.
     */
    protected abstract ObjectNode serializeReference(IBaseReference ref);

    // ========== ObjectMapper ==========

    private ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    // ========== Configuration ==========

    public void setMessageSender(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    public void addListener(SmartMessageListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SmartMessageListener listener) {
        listeners.remove(listener);
    }

    // ========== Inbound message handling ==========

    /**
     * Handle an incoming JSON message from the WebView.
     *
     * @param jsonMessage The raw JSON message string
     * @return The JSON response string, or null if no response is needed
     */
    public String handleMessage(String jsonMessage) {
        logger.debug("Received message for handling: {}", jsonMessage);

        try {
            if (jsonMessage.contains("\"responseToMessageId\"")) {
                logger.debug("Message identified as SmartMessageResponse.");
                SmartMessageResponse response = parseResponse(jsonMessage);
                handleResponseMessage(response);
                return null;
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
            if (messageType == null) {
                return serializeResponse(SmartMessageResponse.createErrorResponse(
                    message.getMessageId(),
                    new ErrorResponse("messageType is required", "MissingFieldException")
                ));
            }
            JsonNode payload = message.getPayload();

            switch (message.getMessageType().toLowerCase(java.util.Locale.ROOT)) {

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
        logger.debug("Created response={}", responseJson);
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
            logger.debug("No listener found for response message with ResponseToMessageId: {}",
                response.getResponseToMessageId());
        }
    }

    private IBaseResource parseResourcePayload(JsonNode payload, String fieldName) {
        JsonNode resourceNode = payload.get(fieldName);
        if (resourceNode == null) {
            throw new IllegalArgumentException(fieldName + " is required in payload");
        }
        return fhirJsonParser.parseResource(resourceNode.toString());
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

        IBaseResource qr = parseResourcePayload(payload, "response");
        IBaseResource outcome = payload.has("outcome") && !payload.get("outcome").isNull()
            ? fhirJsonParser.parseResource(payload.get("outcome").toString())
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

    // ========== Serialization ==========

    private String serializeResponse(SmartMessageResponse response) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("messageId", response.getMessageId());
            node.put("responseToMessageId", response.getResponseToMessageId());
            node.put("additionalResponsesExpected", response.isAdditionalResponsesExpected());

            ResponsePayload payload = response.getPayload();
            if (payload != null) {
                ObjectNode payloadNode = objectMapper.valueToTree(payload);
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

        MessageSender sender = this.messageSender;
        if (sender == null) {
            throw new IllegalStateException("MessageSender must be set before sending messages");
        }

        String messageId = UUID.randomUUID().toString();

        if (responseHandler != null) {
            responseListeners.put(messageId, responseHandler);
            logger.debug("Registered response listener for MessageId: {}", messageId);
        }

        try {
            String requestJson = serializeRequest(messageId, messageType, payload);
            logger.debug("Sending JSON message: {}", requestJson);
            return sender.sendMessage(requestJson);
        } catch (JsonProcessingException e) {
            responseListeners.remove(messageId);
            logger.error("Failed to serialize request", e);
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    private String serializeRequest(String messageId, String messageType, RequestPayload payload) throws JsonProcessingException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("messageId", messageId);
        node.put("messagingHandle", "smart-web-messaging");
        node.put("messageType", messageType);

        if (payload == null) {
            node.set("payload", objectMapper.createObjectNode());
        } else if (payload instanceof SdcDisplayQuestionnaire) {
            node.set("payload", serializeSdcDisplayQuestionnaire((SdcDisplayQuestionnaire) payload));
        } else if (payload instanceof SdcConfigureContext) {
            node.set("payload", serializeSdcConfigureContext((SdcConfigureContext) payload));
        } else {
            node.set("payload", objectMapper.valueToTree(payload));
        }

        return objectMapper.writeValueAsString(node);
    }

    private ObjectNode serializeSdcDisplayQuestionnaire(SdcDisplayQuestionnaire payload) throws JsonProcessingException {
        ObjectNode node = objectMapper.createObjectNode();

        Object questionnaire = payload.getQuestionnaire();
        if (questionnaire instanceof String) {
            node.put("questionnaire", (String) questionnaire);
        } else if (questionnaire instanceof IBaseResource) {
            String json = fhirJsonParser.encodeResourceToString((IBaseResource) questionnaire);
            node.set("questionnaire", objectMapper.readTree(json));
        } else if (questionnaire instanceof IBaseReference) {
            node.set("questionnaire", serializeReference((IBaseReference) questionnaire));
        }

        if (payload.getQuestionnaireResponse() != null) {
            String json = fhirJsonParser.encodeResourceToString(payload.getQuestionnaireResponse());
            node.set("questionnaireResponse", objectMapper.readTree(json));
        }

        if (payload.getContext() != null) {
            node.set("context", serializeSdcDisplayQuestionnaireContext(payload.getContext()));
        }

        return node;
    }

    private ObjectNode serializeSdcDisplayQuestionnaireContext(SdcDisplayQuestionnaire.SdcDisplayQuestionnaireContext context) throws JsonProcessingException {
        ObjectNode node = objectMapper.createObjectNode();

        if (context.getSubject() != null) {
            node.set("subject", serializeReference(context.getSubject()));
        }
        if (context.getAuthor() != null) {
            node.set("author", serializeReference(context.getAuthor()));
        }
        if (context.getEncounter() != null) {
            node.set("encounter", serializeReference(context.getEncounter()));
        }
        if (context.getLaunchContext() != null && !context.getLaunchContext().isEmpty()) {
            ArrayNode launchContextArray = objectMapper.createArrayNode();
            for (LaunchContext lc : context.getLaunchContext()) {
                launchContextArray.add(serializeLaunchContext(lc));
            }
            node.set("launchContext", launchContextArray);
        }

        return node;
    }

    private ObjectNode serializeSdcConfigureContext(SdcConfigureContext payload) throws JsonProcessingException {
        ObjectNode node = objectMapper.createObjectNode();

        if (payload.getSubject() != null) {
            node.set("subject", serializeReference(payload.getSubject()));
        }
        if (payload.getAuthor() != null) {
            node.set("author", serializeReference(payload.getAuthor()));
        }
        if (payload.getEncounter() != null) {
            node.set("encounter", serializeReference(payload.getEncounter()));
        }
        if (payload.getLaunchContext() != null && !payload.getLaunchContext().isEmpty()) {
            ArrayNode launchContextArray = objectMapper.createArrayNode();
            for (LaunchContext lc : payload.getLaunchContext()) {
                launchContextArray.add(serializeLaunchContext(lc));
            }
            node.set("launchContext", launchContextArray);
        }

        return node;
    }

    private ObjectNode serializeLaunchContext(LaunchContext lc) throws JsonProcessingException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", lc.getName());

        if (lc.getContentReference() != null) {
            node.set("contentReference", serializeReference(lc.getContentReference()));
        }
        if (lc.getContentResource() != null) {
            String json = fhirJsonParser.encodeResourceToString(lc.getContentResource());
            node.set("contentResource", objectMapper.readTree(json));
        }

        return node;
    }

    // ========== Non-typed outbound convenience methods ==========

    public CompletableFuture<String> sendFormRequestSubmitAsync(Consumer<SmartMessageResponse> responseHandler) {
        logger.debug("Sending ui.form.requestSubmit message.");
        return sendMessageAsync("ui.form.requestSubmit", new RequestPayload(), responseHandler);
    }

    public CompletableFuture<String> sendFormPersistAsync(Consumer<SmartMessageResponse> responseHandler) {
        logger.debug("Sending ui.form.persist message.");
        return sendMessageAsync("ui.form.persist", new RequestPayload(), responseHandler);
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

    public CompletableFuture<String> sendSdcConfigureContextAsync(
            IBaseReference subject,
            IBaseReference author,
            IBaseReference encounter,
            List<LaunchContext> launchContext,
            Consumer<SmartMessageResponse> responseHandler) {

        logger.debug("Sending sdc.configureContext message.");

        SdcConfigureContext payload = new SdcConfigureContext(subject, author, encounter, launchContext);
        return sendMessageAsync("sdc.configureContext", payload, responseHandler);
    }

    public CompletableFuture<String> sendSdcDisplayQuestionnaireAsync(
            Object questionnaire,
            IBaseResource questionnaireResponse,
            IBaseReference subject,
            IBaseReference author,
            IBaseReference encounter,
            List<LaunchContext> launchContext,
            Consumer<SmartMessageResponse> responseHandler) {

        logger.debug("Sending sdc.displayQuestionnaire message.");

        SdcDisplayQuestionnaire.SdcDisplayQuestionnaireContext context =
            new SdcDisplayQuestionnaire.SdcDisplayQuestionnaireContext(subject, author, encounter, launchContext);

        SdcDisplayQuestionnaire payload = new SdcDisplayQuestionnaire(questionnaire, questionnaireResponse, context);
        return sendMessageAsync("sdc.displayQuestionnaire", payload, responseHandler);
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

    protected ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
