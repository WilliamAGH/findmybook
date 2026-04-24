package net.findmybook.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.findmybook.domain.similarity.BookSimilarityFusionPolicy;
import net.findmybook.domain.similarity.BookSimilarityFusionProfile;
import net.findmybook.domain.similarity.BookSimilaritySectionKey;
import net.findmybook.util.HashUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the book similarity section-fusion policy from the classpath resource.
 */
@Configuration
public class BookSimilarityFusionPolicyConfig {

    private static final String POLICY_RESOURCE = "similarity/book-similarity-profiles.json";

    /**
     * Builds the validated book similarity fusion policy used by embedding refreshes.
     *
     * @param objectMapper application JSON mapper
     * @return validated fusion policy
     */
    @Bean
    public BookSimilarityFusionPolicy bookSimilarityFusionPolicy(ObjectMapper objectMapper) {
        String policyJson = readPolicyJson();
        PolicyFile policyFile = readPolicyFile(objectMapper, policyJson);
        List<BookSimilaritySectionKey> sectionOrder = policyFile.sectionOrder().stream()
            .map(BookSimilaritySectionKey::fromKey)
            .toList();
        List<BookSimilarityFusionProfile> profiles = policyFile.profiles().stream()
            .map(BookSimilarityFusionPolicyConfig::toProfile)
            .toList();
        return new BookSimilarityFusionPolicy(
            policyFile.activeProfileId(),
            sectionOrder,
            profiles,
            sha256Hex(policyJson)
        );
    }

    private static PolicyFile readPolicyFile(ObjectMapper objectMapper, String policyJson) {
        try {
            return objectMapper.readValue(policyJson, PolicyFile.class);
        } catch (JacksonException jacksonException) {
            throw new IllegalStateException("Book similarity policy JSON is invalid: " + POLICY_RESOURCE, jacksonException);
        }
    }

    private static BookSimilarityFusionProfile toProfile(ProfileFile profileFile) {
        EnumMap<BookSimilaritySectionKey, Double> weights = new EnumMap<>(BookSimilaritySectionKey.class);
        profileFile.weights().forEach((sectionKey, weight) ->
            weights.put(BookSimilaritySectionKey.fromKey(sectionKey), weight)
        );
        return new BookSimilarityFusionProfile(profileFile.id(), profileFile.description(), weights);
    }

    private static String readPolicyJson() {
        ClassPathResource resource = new ClassPathResource(POLICY_RESOURCE);
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to read book similarity policy resource: " + POLICY_RESOURCE, ioException);
        }
    }

    private static String sha256Hex(String input) {
        try {
            return HashUtils.sha256Hex(input);
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("SHA-256 is unavailable for book similarity policy hashing", noSuchAlgorithmException);
        }
    }

    private record PolicyFile(String activeProfileId, List<String> sectionOrder, List<ProfileFile> profiles) {
    }

    private record ProfileFile(String id, String description, Map<String, Double> weights) {
    }
}
