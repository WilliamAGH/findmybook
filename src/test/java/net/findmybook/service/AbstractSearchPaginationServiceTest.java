package net.findmybook.service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.dto.BookListItem;
import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import net.findmybook.util.ApplicationConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared fixture and factory support for {@link SearchPaginationService} tests.
 */
@ExtendWith(MockitoExtension.class)
abstract class AbstractSearchPaginationServiceTest {

    @Mock
    protected BookSearchService bookSearchService;

    @Mock
    protected BookQueryRepository bookQueryRepository;

    @Mock
    protected GoogleApiFetcher googleApiFetcher;

    @Mock
    protected GoogleBooksMapper googleBooksMapper;

    @Mock
    protected OpenLibraryBookDataService openLibraryBookDataService;

    @Mock
    protected BookDataOrchestrator bookDataOrchestrator;

    @Mock
    protected ApplicationEventPublisher eventPublisher;

    protected SearchPaginationService service;

    @BeforeEach
    void initializeDefaultSearchService() {
        service = postgresOnlyService();
    }

    protected SearchPaginationService postgresOnlyService() {
        return new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            Optional.empty(),
            Optional.empty()
        );
    }

    protected SearchPaginationService fallbackEnabledService() {
        return new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            Optional.of(googleApiFetcher),
            Optional.of(googleBooksMapper),
            Optional.of(openLibraryBookDataService),
            Optional.of(bookDataOrchestrator),
            Optional.of(eventPublisher),
            true
        );
    }

    protected SearchPaginationService.SearchRequest searchRequest(String query,
                                                                  int startIndex,
                                                                  int maxResults,
                                                                  String orderBy) {
        return new SearchPaginationService.SearchRequest(
            query,
            startIndex,
            maxResults,
            orderBy,
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );
    }

    protected SearchPaginationService.SearchRequest searchRequest(String query,
                                                                  int startIndex,
                                                                  int maxResults,
                                                                  String orderBy,
                                                                  Integer publishedYear) {
        return new SearchPaginationService.SearchRequest(
            query,
            startIndex,
            maxResults,
            orderBy,
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY,
            publishedYear
        );
    }

    protected BookListItem buildListItem(UUID id, String title) {
        Map<String, Object> tags = Map.of("nytBestseller", Map.of("rank", 1));
        return new BookListItem(
            id.toString(),
            "slug-" + id,
            title,
            title + " description",
            List.of("Fixture Author"),
            List.of("Fixture Category"),
            "https://example.test/" + id + ".jpg",
            "s3://covers/" + id + ".jpg",
            "https://fallback.example.test/" + id + ".jpg",
            600,
            900,
            true,
            4.5,
            100,
            tags
        );
    }

    protected BookListItem buildListItem(UUID id,
                                         String title,
                                         int width,
                                         int height,
                                         boolean highResolution,
                                         String coverUrl) {
        return buildListItem(id, title, List.of("Fixture Author"), width, height, highResolution, coverUrl, null);
    }

    protected BookListItem buildListItem(UUID id,
                                         String title,
                                         int width,
                                         int height,
                                         boolean highResolution,
                                         String coverUrl,
                                         LocalDate publishedDate) {
        return buildListItem(id, title, List.of("Fixture Author"), width, height, highResolution, coverUrl, publishedDate);
    }

    protected BookListItem buildListItem(UUID id,
                                         String title,
                                         List<String> authors,
                                         int width,
                                         int height,
                                         boolean highResolution,
                                         String coverUrl) {
        return buildListItem(id, title, authors, width, height, highResolution, coverUrl, null);
    }

    protected BookListItem buildListItem(UUID id,
                                         String title,
                                         List<String> authors,
                                         int width,
                                         int height,
                                         boolean highResolution,
                                         String coverUrl,
                                         LocalDate publishedDate) {
        return new BookListItem(
            id.toString(),
            "slug-" + id,
            title,
            title + " description",
            authors,
            List.of("Fixture Category"),
            coverUrl,
            "s3://covers/" + id + ".jpg",
            "https://fallback.example.test/" + id + ".jpg",
            width,
            height,
            highResolution,
            4.0,
            25,
            Map.of(),
            publishedDate
        );
    }

    protected Book buildOpenLibraryCandidate(String id, String title) {
        Book book = new Book();
        book.setId(id);
        book.setTitle(title);
        book.setAuthors(List.of("Open Library Author"));
        book.setRetrievedFrom("OPEN_LIBRARY");
        book.setDataSource("OPEN_LIBRARY");
        return book;
    }

    protected BookAggregate googleAggregate(String externalId, String title, String imageUrl) {
        return BookAggregate.builder()
            .title(title)
            .authors(List.of("Google Author"))
            .slugBase(title.toLowerCase().replace(' ', '-'))
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId(externalId)
                .imageLinks(Map.of("thumbnail", imageUrl))
                .build())
            .build();
    }

    protected ObjectNode googleVolumeNode(String id, String title) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        node.putObject("volumeInfo").put("title", title);
        return node;
    }

    protected static boolean isGoogleRealtimeEvent(Object event) {
        return event instanceof SearchResultsUpdatedEvent updatedEvent
            && "GOOGLE_BOOKS".equals(updatedEvent.getSource())
            && updatedEvent.getNewResults() != null
            && !updatedEvent.getNewResults().isEmpty();
    }

    protected static BookListItem buildNullEquivalentCoverListItem(UUID id, String title) {
        return new BookListItem(
            id.toString(),
            "slug-" + id,
            title,
            title + " description",
            List.of("Fixture Author"),
            List.of("Fixture Category"),
            null,
            "null",
            ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH,
            null,
            null,
            null,
            3.5,
            12,
            Map.of()
        );
    }
}
