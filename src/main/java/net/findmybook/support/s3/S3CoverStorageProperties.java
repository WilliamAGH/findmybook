package net.findmybook.support.s3;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for S3-backed cover storage behavior.
 */
@ConfigurationProperties(prefix = "s3")
public record S3CoverStorageProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("true") boolean writeEnabled,
    @DefaultValue(".jpg") String coverDefaultFileExtension,
    @DefaultValue({"google-books", "open-library", "longitood"}) List<String> coverSourceBaseLabels
) {}
