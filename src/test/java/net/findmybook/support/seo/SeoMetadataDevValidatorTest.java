package net.findmybook.support.seo;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeoMetadataDevValidatorTest {

    @Test
    void should_ReturnNoWarnings_When_ProfileIsNotDev() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        SeoMetadataDevValidator validator = new SeoMetadataDevValidator(environment);

        List<String> warnings = validator.validateSpaHead(
            "A Valid Book Title",
            "A concise and valid description for a metadata payload.",
            "https://findmybook.net/book/the-hobbit",
            "https://findmybook.net/api/pages/og/book/the-hobbit",
            "book",
            "findmybook",
            "image/png",
            1200,
            630,
            "summary_large_image"
        );

        assertTrue(warnings.isEmpty());
    }

    @Test
    void should_ReturnWarnings_When_DevMetadataViolatesOpenGraphRequirements() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        SeoMetadataDevValidator validator = new SeoMetadataDevValidator(environment);

        List<String> warnings = validator.validateSpaHead(
            "This title is intentionally far longer than sixty characters so warning checks trigger",
            "This description is intentionally long enough to exceed the recommended one hundred sixty characters for social metadata previews and should be flagged by the development validator.",
            "http://localhost:8095/book/test-book",
            "/api/pages/og/book/test-book",
            "",
            "",
            "image/svg+xml",
            100,
            50,
            ""
        );

        assertTrue(warnings.stream().anyMatch(value -> value.contains("title exceeds recommended 60 characters")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("description exceeds recommended 160 characters")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("canonical URL must be publicly reachable")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("og:image must be absolute")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("og:type is required but blank")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("og:site_name is required but blank")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("twitter:card is required but blank")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("og:image:type is not in supported set")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("og:image dimensions are below minimum")));
    }

    @Test
    void should_ReturnNoWarnings_When_DevMetadataPassesOpenGraphChecks() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        SeoMetadataDevValidator validator = new SeoMetadataDevValidator(environment);

        List<String> warnings = validator.validateSpaHead(
            "The Hobbit",
            "Discover editions, metadata, and recommendations for The Hobbit.",
            "https://findmybook.net/book/the-hobbit",
            "https://findmybook.net/api/pages/og/book/the-hobbit",
            "book",
            "findmybook",
            "image/png",
            1200,
            630,
            "summary_large_image"
        );

        assertTrue(warnings.isEmpty());
    }
}
