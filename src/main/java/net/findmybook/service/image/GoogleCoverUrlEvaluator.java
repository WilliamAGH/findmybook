package net.findmybook.service.image;

import net.findmybook.util.GoogleBooksUrlEnhancer;
import org.springframework.stereotype.Component;

/**
 * Helper for evaluating Google Books cover URLs.
 */
@Component
public class GoogleCoverUrlEvaluator {

    public boolean isAcceptableUrl(String url) {
        return GoogleBooksUrlEnhancer.isGoogleBooksUrl(url);
    }

    public boolean hasFrontCoverHint(String url) {
        return GoogleBooksUrlEnhancer.hasFrontCoverHint(url);
    }
}
