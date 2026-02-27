package health.tiro.formfiller.swing;

/**
 * Configuration for {@link FormFiller}.
 * Use {@link #builder()} to create instances.
 *
 * <p>Either provide a custom {@code targetUrl} or let the library generate a default page
 * by specifying {@code sdcEndpointAddress} (and optionally {@code sdkUrl}).
 *
 * <pre>{@code
 * // Use the built-in default page
 * FormFillerConfig config = FormFillerConfig.builder()
 *     .sdcEndpointAddress("http://localhost:8000/fhir/r5")
 *     .build();
 *
 * // Or bring your own page
 * FormFillerConfig config = FormFillerConfig.builder()
 *     .targetUrl("https://your-form-app.com/form-filler.html")
 *     .build();
 * }</pre>
 */
public class FormFillerConfig {

    static final String DEFAULT_SDK_URL =
        "https://cdn.tiro.health/sdk/latest/tiro-web-sdk.iife.js";

    private final String targetUrl;
    private final String sdcEndpointAddress;
    private final String dataEndpointAddress;
    private final String sdkUrl;
    private final long handshakeTimeoutSeconds;

    private FormFillerConfig(Builder builder) {
        this.targetUrl = builder.targetUrl;
        this.sdcEndpointAddress = builder.sdcEndpointAddress;
        this.dataEndpointAddress = builder.dataEndpointAddress;
        this.sdkUrl = builder.sdkUrl;
        this.handshakeTimeoutSeconds = builder.handshakeTimeoutSeconds;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public String getSdcEndpointAddress() {
        return sdcEndpointAddress;
    }

    public String getDataEndpointAddress() {
        return dataEndpointAddress;
    }

    public String getSdkUrl() {
        return sdkUrl;
    }

    public long getHandshakeTimeoutSeconds() {
        return handshakeTimeoutSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String targetUrl;
        private String sdcEndpointAddress;
        private String dataEndpointAddress;
        private String sdkUrl = DEFAULT_SDK_URL;
        private long handshakeTimeoutSeconds = 30;

        private Builder() {}

        /**
         * Set the URL to load in the embedded browser.
         * When set, {@code sdcEndpointAddress} and {@code sdkUrl} are ignored.
         */
        public Builder targetUrl(String targetUrl) {
            this.targetUrl = targetUrl;
            return this;
        }

        /**
         * Set the SDC FHIR endpoint URL used by the default page.
         * Required when {@code targetUrl} is not set.
         */
        public Builder sdcEndpointAddress(String sdcEndpointAddress) {
            this.sdcEndpointAddress = sdcEndpointAddress;
            return this;
        }

        /**
         * Set the FHIR data endpoint URL used by the default page.
         * Maps to the {@code data-endpoint-address} attribute on the form filler element.
         */
        public Builder dataEndpointAddress(String dataEndpointAddress) {
            this.dataEndpointAddress = dataEndpointAddress;
            return this;
        }

        /**
         * Set the Tiro Web SDK script URL used by the default page.
         * Defaults to the latest dev build on the Tiro CDN.
         */
        public Builder sdkUrl(String sdkUrl) {
            this.sdkUrl = sdkUrl;
            return this;
        }

        /**
         * Set the maximum time to wait for the JS handshake (default: 30 seconds).
         */
        public Builder handshakeTimeoutSeconds(long handshakeTimeoutSeconds) {
            this.handshakeTimeoutSeconds = handshakeTimeoutSeconds;
            return this;
        }

        public FormFillerConfig build() {
            if (targetUrl == null || targetUrl.trim().isEmpty()) {
                if (sdcEndpointAddress == null || sdcEndpointAddress.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                        "Either targetUrl or sdcEndpointAddress is required");
                }
            }
            return new FormFillerConfig(this);
        }
    }
}
