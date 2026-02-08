package net.findmybook.testutil;

import net.findmybook.model.image.CoverImageSource;
import net.findmybook.service.event.BookCoverUpdatedEvent;
import org.mockito.ArgumentMatcher;

/** Common Mockito argument matchers used in tests. */
public final class EventMatchers {
    private EventMatchers() {}

    public static ArgumentMatcher<BookCoverUpdatedEvent> bookCoverUpdated(String identifierKey,
                                                                          String expectedUrl,
                                                                          CoverImageSource expectedSource) {
        return event -> event != null
                && identifierKey.equals(event.getIdentifierKey())
                && expectedUrl.equals(event.getNewCoverUrl())
                && expectedSource == event.getSource();
    }
}