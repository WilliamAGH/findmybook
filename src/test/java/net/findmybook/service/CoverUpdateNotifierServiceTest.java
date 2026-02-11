package net.findmybook.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.findmybook.application.cover.CoverS3UploadCoordinator;
import net.findmybook.application.realtime.CoverRealtimePayloadFactory;
import net.findmybook.model.Book;
import net.findmybook.service.event.BookCoverUpdatedEvent;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.service.event.SearchProgressEvent;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.core.MessageSendingOperations;

class CoverUpdateNotifierServiceTest {

    private MessageSendingOperations<String> messagingTemplate;
    private CoverRealtimePayloadFactory payloadFactory;
    private CoverS3UploadCoordinator coverS3UploadCoordinator;
    private CoverUpdateNotifierService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        MessageSendingOperations<String> mockedTemplate = Mockito.mock(MessageSendingOperations.class);
        messagingTemplate = mockedTemplate;
        payloadFactory = Mockito.mock(CoverRealtimePayloadFactory.class);
        coverS3UploadCoordinator = Mockito.mock(CoverS3UploadCoordinator.class);
        service = new CoverUpdateNotifierService(messagingTemplate, payloadFactory, coverS3UploadCoordinator);
    }

    @Test
    void should_SendCoverUpdatePayload_When_BookCoverEventIsValid() {
        BookCoverUpdatedEvent event = new BookCoverUpdatedEvent("isbn13:9780132350884", "https://cdn.example.com/covers/clean-code.webp", "google-book-id");
        CoverRealtimePayloadFactory.CoverUpdatePayload payload =
            new CoverRealtimePayloadFactory.CoverUpdatePayload(
                "google-book-id",
                "https://cdn.example.com/covers/clean-code.webp",
                "isbn13:9780132350884",
                "UNDEFINED",
                "Undefined Source"
            );
        when(payloadFactory.createCoverUpdatePayload(event)).thenReturn(payload);

        service.handleBookCoverUpdated(event);

        verify(messagingTemplate, times(1)).convertAndSend("/topic/book/google-book-id/coverUpdate", payload);
        verify(coverS3UploadCoordinator, never()).triggerUpload(Mockito.any());
    }

    @Test
    void should_SendSearchResultsPayload_When_SearchResultsEventIsValid() {
        Book book = new Book();
        book.setId("book-1");

        SearchResultsUpdatedEvent event = new SearchResultsUpdatedEvent(
            "clean code",
            List.of(book),
            "GOOGLE_BOOKS",
            1,
            "clean_code",
            false
        );

        CoverRealtimePayloadFactory.SearchResultsPayload payload =
            new CoverRealtimePayloadFactory.SearchResultsPayload("clean code", "GOOGLE_BOOKS", 1, false, 1, List.of());
        when(payloadFactory.createSearchResultsPayload(event)).thenReturn(payload);

        service.handleSearchResultsUpdated(event);

        verify(messagingTemplate, times(1)).convertAndSend("/topic/search/clean_code/results", payload);
        verify(coverS3UploadCoordinator, never()).triggerUpload(Mockito.any());
    }

    @Test
    void should_SendSearchProgressPayload_When_SearchProgressEventIsValid() {
        SearchProgressEvent event = new SearchProgressEvent(
            "clean code",
            SearchProgressEvent.SearchStatus.SEARCHING_GOOGLE,
            "Searching Google Books",
            "clean_code",
            "GOOGLE_BOOKS"
        );

        CoverRealtimePayloadFactory.SearchProgressPayload payload =
            new CoverRealtimePayloadFactory.SearchProgressPayload("clean code", "SEARCHING_GOOGLE", "Searching Google Books", "GOOGLE_BOOKS");
        when(payloadFactory.createSearchProgressPayload(event)).thenReturn(payload);

        service.handleSearchProgress(event);

        verify(messagingTemplate, times(1)).convertAndSend("/topic/search/clean_code/progress", payload);
    }

    @Test
    void should_SendBookUpsertPayloadAndTriggerUpload_When_BookUpsertEventIsValid() {
        String bookId = UUID.randomUUID().toString();
        BookUpsertEvent event = new BookUpsertEvent(
            bookId,
            "clean-code",
            "Clean Code",
            true,
            "GOOGLE_BOOKS",
            Map.of("thumbnail", "https://covers.example.com/clean-code.jpg"),
            "https://covers.example.com/clean-code.jpg",
            "GOOGLE_BOOKS"
        );

        CoverRealtimePayloadFactory.BookUpsertPayload payload =
            new CoverRealtimePayloadFactory.BookUpsertPayload(bookId, "clean-code", "Clean Code", true, "GOOGLE_BOOKS", "https://covers.example.com/clean-code.jpg");
        when(payloadFactory.createBookUpsertPayload(event)).thenReturn(payload);

        service.handleBookUpsert(event);

        verify(messagingTemplate, times(1)).convertAndSend("/topic/book/" + bookId + "/upsert", payload);
        verify(coverS3UploadCoordinator, times(1)).triggerUpload(event);
    }

    @Test
    void should_TriggerUploadForNytUpsert_When_NytSourceEventArrives() {
        String bookId = UUID.randomUUID().toString();
        BookUpsertEvent event = new BookUpsertEvent(
            bookId,
            "the-book-thief",
            "The Book Thief",
            false,
            "NEW_YORK_TIMES",
            Map.of("thumbnail", "https://static01.nyt.com/images/test.jpg"),
            "https://static01.nyt.com/images/test.jpg",
            "NEW_YORK_TIMES"
        );
        CoverRealtimePayloadFactory.BookUpsertPayload payload =
            new CoverRealtimePayloadFactory.BookUpsertPayload(
                bookId,
                "the-book-thief",
                "The Book Thief",
                false,
                "NEW_YORK_TIMES",
                "https://static01.nyt.com/images/test.jpg"
            );
        when(payloadFactory.createBookUpsertPayload(event)).thenReturn(payload);

        service.handleBookUpsert(event);

        verify(messagingTemplate).convertAndSend("/topic/book/" + bookId + "/upsert", payload);
        verify(coverS3UploadCoordinator).triggerUpload(event);
    }

    @Test
    void should_SkipBookUpsertDispatch_When_BookIdMissing() {
        BookUpsertEvent event = new BookUpsertEvent(
            " ",
            "clean-code",
            "Clean Code",
            false,
            "GOOGLE_BOOKS",
            Map.of(),
            null,
            "GOOGLE_BOOKS"
        );

        service.handleBookUpsert(event);

        verifyNoInteractions(messagingTemplate);
        verify(coverS3UploadCoordinator, never()).triggerUpload(Mockito.any());
        verifyNoInteractions(payloadFactory);
    }
}
