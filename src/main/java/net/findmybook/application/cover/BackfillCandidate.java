package net.findmybook.application.cover;

import java.util.UUID;
import org.springframework.util.StringUtils;

record BackfillCandidate(UUID id, String title, String isbn13, String isbn10) {
    String preferredIsbn() {
        return StringUtils.hasText(isbn13) ? isbn13 : isbn10;
    }

    String displayTitle() {
        return StringUtils.hasText(title) ? title : "<untitled>";
    }
}
