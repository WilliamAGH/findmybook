package net.findmybook.support.s3;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for S3-backed cover storage behavior.
 */
@Component
@ConfigurationProperties(prefix = "s3")
public class S3CoverStorageProperties {

    private boolean enabled = true;
    private boolean writeEnabled = true;
    private String coverDefaultFileExtension = ".jpg";
    private List<String> coverSourceBaseLabels = new ArrayList<>(List.of("google-books", "open-library", "longitood"));

    /**
     * Indicates if S3 cover reads are enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Indicates if S3 cover writes are enabled.
     */
    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public void setWriteEnabled(boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    /**
     * Returns the default cover extension used for lookups.
     */
    public String getCoverDefaultFileExtension() {
        return coverDefaultFileExtension;
    }

    public void setCoverDefaultFileExtension(String coverDefaultFileExtension) {
        this.coverDefaultFileExtension = coverDefaultFileExtension;
    }

    /**
     * Returns preferred source labels in lookup priority order.
     */
    public List<String> getCoverSourceBaseLabels() {
        return coverSourceBaseLabels;
    }

    public void setCoverSourceBaseLabels(List<String> coverSourceBaseLabels) {
        this.coverSourceBaseLabels = coverSourceBaseLabels;
    }
}
