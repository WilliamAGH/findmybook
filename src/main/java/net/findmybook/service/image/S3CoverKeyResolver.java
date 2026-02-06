package net.findmybook.service.image;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import net.findmybook.util.cover.S3KeyGenerator;
import org.springframework.stereotype.Component;

/**
 * Produces canonical and legacy-compatible S3 keys for cover lookups.
 */
@Component
public class S3CoverKeyResolver {

    public String generateCanonicalKey(String bookId, String fileExtension, String source) {
        return S3KeyGenerator.generateCoverKeyFromRawSource(bookId, fileExtension, source);
    }

    public List<String> buildCandidateKeys(String bookId, String fileExtension, String rawSource) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String segment : candidateSegmentsFor(rawSource)) {
            keys.add(S3KeyGenerator.generateCoverKeyFromRawSource(bookId, fileExtension, segment));
        }
        return new ArrayList<>(keys);
    }

    private List<String> candidateSegmentsFor(String rawSource) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();

        if (rawSource != null) {
            String trimmed = rawSource.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                variants.add(trimmed);
                variants.add(trimmed.replace(' ', '-'));
                variants.add(trimmed.replace(' ', '_'));
            }
        }

        String canonical = S3KeyGenerator.normalizeRawSource(rawSource);
        if (canonical != null && !canonical.isBlank()) {
            variants.add(canonical);
            variants.add(canonical.replace('-', '_'));
            variants.add(canonical.replace('_', '-'));
        }

        variants.removeIf(String::isBlank);
        return new ArrayList<>(variants);
    }
}
