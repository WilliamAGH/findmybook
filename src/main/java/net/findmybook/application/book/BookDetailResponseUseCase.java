package net.findmybook.application.book;

import jakarta.annotation.Nullable;
import java.util.Optional;
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.controller.dto.BookAiContentSnapshotDto;
import net.findmybook.controller.dto.BookDto;
import net.findmybook.model.Book;
import net.findmybook.service.RecentBookViewRepository;
import net.findmybook.service.RecentlyViewedService;
import net.findmybook.util.UuidUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Builds enriched book detail API payloads from canonical {@link BookDto} records.
 *
 * <p>This use case centralizes detail-response concerns that are not HTTP translation:
 * recording recently-viewed events, attaching cached AI snapshots, and adding
 * optional rolling view metrics.</p>
 */
@Service
public class BookDetailResponseUseCase {

    private static final String INVALID_VIEW_WINDOW_DETAIL =
        "Invalid viewWindow parameter: supported values are 30d, 90d, all";

    private final BookAiContentService bookAiContentService;
    private final RecentlyViewedService recentlyViewedService;

    /**
     * Creates the detail response use case.
     *
     * @param bookAiContentService service for cached AI snapshot lookups
     * @param recentlyViewedService service for recording and reading detail view metrics
     */
    public BookDetailResponseUseCase(BookAiContentService bookAiContentService,
                                     RecentlyViewedService recentlyViewedService) {
        this.bookAiContentService = bookAiContentService;
        this.recentlyViewedService = recentlyViewedService;
    }

    /**
     * Enriches a canonical detail payload using optional request query arguments.
     *
     * @param bookDto canonical book detail payload
     * @param rawViewWindow requested rolling window query value ({@code 30d}, {@code 90d}, {@code all})
     * @return enriched detail payload with AI snapshot and optional view metrics
     */
    public BookDto enrichDetailResponse(BookDto bookDto, @Nullable String rawViewWindow) {
        if (bookDto == null) {
            return null;
        }

        Optional<RecentBookViewRepository.ViewWindow> requestedWindow = parseViewWindow(rawViewWindow);
        recordRecentlyViewed(bookDto);

        BookDto enriched = attachAiContentSnapshot(bookDto);
        if (requestedWindow.isEmpty()) {
            return enriched;
        }
        return attachViewMetrics(enriched, requestedWindow.get());
    }

    /**
     * Validates the detail view window query value and fails fast on unsupported values.
     *
     * @param rawViewWindow requested rolling window query value
     */
    public void validateViewWindow(@Nullable String rawViewWindow) {
        parseViewWindow(rawViewWindow);
    }

    private Optional<RecentBookViewRepository.ViewWindow> parseViewWindow(@Nullable String rawViewWindow) {
        if (!StringUtils.hasText(rawViewWindow)) {
            return Optional.empty();
        }

        Optional<RecentBookViewRepository.ViewWindow> parsed =
            RecentBookViewRepository.ViewWindow.fromQueryValue(rawViewWindow);
        if (parsed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_VIEW_WINDOW_DETAIL);
        }
        return parsed;
    }

    private void recordRecentlyViewed(BookDto bookDto) {
        if (bookDto == null || !StringUtils.hasText(bookDto.id())) {
            return;
        }

        Book viewedBook = new Book();
        viewedBook.setId(bookDto.id());
        viewedBook.setSlug(StringUtils.hasText(bookDto.slug()) ? bookDto.slug() : bookDto.id());
        viewedBook.setTitle(bookDto.title());
        recentlyViewedService.addToRecentlyViewed(viewedBook);
    }

    private BookDto attachAiContentSnapshot(BookDto bookDto) {
        if (bookDto == null || !StringUtils.hasText(bookDto.id())) {
            return bookDto;
        }

        java.util.UUID bookId = UuidUtils.parseUuidOrNull(bookDto.id());
        if (bookId == null) {
            return bookDto;
        }

        return bookAiContentService.findCurrent(bookId)
            .map(BookAiContentSnapshotDto::fromSnapshot)
            .map(bookDto::withAiContent)
            .orElse(bookDto);
    }

    private BookDto attachViewMetrics(BookDto bookDto, RecentBookViewRepository.ViewWindow viewWindow) {
        if (bookDto == null || viewWindow == null || !StringUtils.hasText(bookDto.id())) {
            return bookDto;
        }

        long totalViews = recentlyViewedService.fetchViewCount(bookDto.id(), viewWindow);
        return bookDto.withViewMetrics(new BookDto.ViewMetricsDto(viewWindow.queryValue(), totalViews));
    }
}
