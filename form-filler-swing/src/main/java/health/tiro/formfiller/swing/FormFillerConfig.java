package health.tiro.formfiller.swing;

/**
 * Configuration for {@link FormFiller}.
 * Use {@link #builder()} to create instances.
 *
 * <p>Either provide a custom {@code targetUrl} or let the library generate a default page
 * by specifying {@code sdcEndpointAddress}.
 *
 * <pre>{@code
 * // Use the built-in default page
 * FormFillerConfig config = FormFillerConfig.builder()
 *     .sdcEndpointAddress("http://localhost:8000/fhir/r5")
 *     .build();
 *
 * // Or bring your own page — it must NOT load tiro-web-sdk itself
 * FormFillerConfig config = FormFillerConfig.builder()
 *     .targetUrl("file:///opt/ehr/form-filler.html")
 *     .build();
 * }</pre>
 *
 * <p>There is no SDK-URL option: the harness embeds and serves the exact
 * {@code @tiro-health/web-sdk} bundle it was validated against, and the bridge injects it
 * (GH-24). A page that loads its own copy — or one the embedded bundle cannot reach, such as
 * a page served over http(s), which may not load a {@code file://} script — fails the
 * handshake with a {@link WebSdkLoadException}.
 */
public class FormFillerConfig {

    private final String targetUrl;
    private final String sdcEndpointAddress;
    private final String dataEndpointAddress;
    private final long handshakeTimeoutSeconds;

    private FormFillerConfig(Builder builder) {
        this.targetUrl = builder.targetUrl;
        this.sdcEndpointAddress = builder.sdcEndpointAddress;
        this.dataEndpointAddress = builder.dataEndpointAddress;
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
        private long handshakeTimeoutSeconds = 30;

        private Builder() {}

        /**
         * Set the URL to load in the embedded browser.
         * When set, {@code sdcEndpointAddress} and {@code dataEndpointAddress} are ignored —
         * the page is responsible for its own {@code <tiro-form-filler>} attributes.
         *
         * <p>The page must not load {@code tiro-web-sdk} itself, and must be reachable by a
         * {@code file://} script (so: a local page). See the class javadoc.
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
