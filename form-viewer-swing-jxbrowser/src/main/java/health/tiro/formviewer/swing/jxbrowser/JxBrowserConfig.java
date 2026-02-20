package health.tiro.formviewer.swing.jxbrowser;

import com.teamdev.jxbrowser.engine.Language;
import com.teamdev.jxbrowser.engine.RenderingMode;

/**
 * Configuration for {@link JxBrowserAdapter}.
 * Use {@link #builder()} to create instances.
 *
 * <pre>{@code
 * JxBrowserConfig config = JxBrowserConfig.builder()
 *     .licenseKey("YOUR-LICENSE-KEY")
 *     .language(Language.ENGLISH_US)
 *     .build();
 * }</pre>
 */
public class JxBrowserConfig {

    private final String licenseKey;
    private final Language language;
    private final RenderingMode renderingMode;

    private JxBrowserConfig(Builder builder) {
        this.licenseKey = builder.licenseKey;
        this.language = builder.language;
        this.renderingMode = builder.renderingMode;
    }

    public String getLicenseKey() {
        return licenseKey;
    }

    public Language getLanguage() {
        return language;
    }

    public RenderingMode getRenderingMode() {
        return renderingMode;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String licenseKey;
        private Language language = Language.ENGLISH_US;
        private RenderingMode renderingMode = RenderingMode.HARDWARE_ACCELERATED;

        private Builder() {}

        /**
         * Set the JxBrowser license key (required).
         */
        public Builder licenseKey(String licenseKey) {
            this.licenseKey = licenseKey;
            return this;
        }

        /**
         * Set the browser language (default: {@link Language#DUTCH}).
         */
        public Builder language(Language language) {
            this.language = language;
            return this;
        }

        /**
         * Set the rendering mode (default: {@link RenderingMode#HARDWARE_ACCELERATED}).
         */
        public Builder renderingMode(RenderingMode renderingMode) {
            this.renderingMode = renderingMode;
            return this;
        }

        public JxBrowserConfig build() {
            if (licenseKey == null || licenseKey.trim().isEmpty()) {
                throw new IllegalArgumentException("licenseKey is required");
            }
            return new JxBrowserConfig(this);
        }
    }
}
