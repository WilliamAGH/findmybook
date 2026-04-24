/**
 * Configuration for feature flags controlling application functionality
 *
 * @author William Callahan
 *
 * Features:
 * - Centralizes feature toggles for the application
 * - Provides bean configuration for runtime feature state
 * - Supports property-based configuration for features
 * - Enables toggling features without code changes
 * - Default values ensure consistent behavior when properties are not provided
 * - Exposes feature state through bean methods for dependency injection
 */
package net.findmybook.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeatureFlagConfig {

    private final boolean yearFilteringEnabled;

    public FeatureFlagConfig(
            @Value("${app.feature.year-filtering.enabled:true}") boolean yearFilteringEnabled) {
        this.yearFilteringEnabled = yearFilteringEnabled;
    }

    /**
     * Provides the year filtering feature state
     *
     * @return True if year filtering is enabled, false otherwise
     *
     * @implNote Defaults to true when property is not specified
     * Controls filtering of books by publication year
     */
    @Bean
    public boolean isYearFilteringEnabled() {
        return yearFilteringEnabled;
    }
}
