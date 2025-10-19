package com.williamcallahan.book_recommendation_engine.util;

import com.williamcallahan.book_recommendation_engine.dto.BookAggregate;
import com.williamcallahan.book_recommendation_engine.dto.BookCard;
import com.williamcallahan.book_recommendation_engine.dto.BookDetail;
import com.williamcallahan.book_recommendation_engine.dto.BookListItem;
import com.williamcallahan.book_recommendation_engine.dto.EditionSummary;
import com.williamcallahan.book_recommendation_engine.model.Book;
import com.williamcallahan.book_recommendation_engine.model.Book.EditionInfo;
import com.williamcallahan.book_recommendation_engine.model.image.CoverImages;
import com.williamcallahan.book_recommendation_engine.util.cover.CoverUrlResolver;
import com.williamcallahan.book_recommendation_engine.util.cover.ImageDimensionUtils;
import com.williamcallahan.book_recommendation_engine.util.cover.UrlSourceDetector;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts projection DTOs into legacy {@link Book} instances while the
 * application transitions toward DTO-first controllers. This centralizes
 * the remaining bridge logic so deprecated mappers can be retired without
 * duplicating conversion code across controllers and schedulers.
 */
public final class BookDomainMapper {

    private static final String SUPPRESSED_FLAG_KEY = "cover.suppressed";
    private static final String SUPPRESSED_REASON_KEY = "cover.suppressed.reason";
    private static final String SUPPRESSED_MIN_HEIGHT_KEY = "cover.suppressed.minHeight";

    private BookDomainMapper() {
    }

    public static Book fromDetail(BookDetail detail) {
        if (detail == null) {
            return null;
        }

        Book book = base(detail.id(), detail.slug(), detail.title(), detail.authors());
        book.setDescription(detail.description());
        book.setPublisher(detail.publisher());
        book.setPublishedDate(toDate(detail.publishedDate()));
        book.setLanguage(detail.language());
        book.setPageCount(detail.pageCount());
        book.setCategories(detail.categories());
        book.setAverageRating(detail.averageRating());
        book.setRatingsCount(detail.ratingsCount());
        book.setIsbn10(detail.isbn10());
        book.setIsbn13(detail.isbn13());
        book.setPreviewLink(detail.previewLink());
        book.setInfoLink(detail.infoLink());
        book.setQualifiers(copyMap(detail.tags()));
        book.setOtherEditions(toEditionInfo(detail.editions()));
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            detail.coverS3Key(),
            detail.coverFallbackUrl(),
            detail.coverWidth(),
            detail.coverHeight(),
            detail.coverHighResolution()
        );
        String fallback = ValidationUtils.hasText(detail.coverFallbackUrl())
            ? detail.coverFallbackUrl()
            : detail.thumbnailUrl();
        setCoverImages(book, resolved, fallback);
        applyCoverMetadata(book, resolved.width(), resolved.height(), resolved.highResolution(), false);
        book.setDataSource(detail.dataSource());
        book.setRetrievedFrom("POSTGRES");
        book.setInPostgres(true);
        return book;
    }

    public static Book fromCard(BookCard card) {
        if (card == null) {
            return null;
        }
        Book book = base(card.id(), card.slug(), card.title(), card.authors());
        book.setAverageRating(card.averageRating());
        book.setRatingsCount(card.ratingsCount());
        book.setQualifiers(copyMap(card.tags()));
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            card.coverS3Key(),
            card.fallbackCoverUrl()
        );
        String fallback = card.fallbackCoverUrl();
        if (!ValidationUtils.hasText(fallback)) {
            fallback = card.coverUrl();
        }
        setCoverImages(book, resolved, fallback);
        applyCoverMetadata(book, resolved.width(), resolved.height(), resolved.highResolution(), true);
        book.setRetrievedFrom("POSTGRES");
        book.setInPostgres(true);
        return book;
    }

    public static List<Book> fromCards(List<BookCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return List.of();
        }
        return cards.stream()
            .map(BookDomainMapper::fromCard)
            .filter(Objects::nonNull)
            .toList();
    }

    public static Book fromListItem(BookListItem item) {
        if (item == null) {
            return null;
        }
        Book book = base(item.id(), item.slug(), item.title(), item.authors());
        book.setDescription(item.description());
        book.setCategories(item.categories());
        book.setAverageRating(item.averageRating());
        book.setRatingsCount(item.ratingsCount());
        book.setQualifiers(copyMap(item.tags()));
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            item.coverS3Key(),
            item.coverFallbackUrl(),
            item.coverWidth(),
            item.coverHeight(),
            item.coverHighResolution()
        );
        String fallback = item.coverFallbackUrl();
        if (!ValidationUtils.hasText(fallback)) {
            fallback = item.coverUrl();
        }
        setCoverImages(book, resolved, fallback);
        applyCoverMetadata(book, resolved.width(), resolved.height(), resolved.highResolution(), true);
        book.setRetrievedFrom("POSTGRES");
        book.setInPostgres(true);
        return book;
    }

    public static List<Book> fromListItems(List<BookListItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
            .map(BookDomainMapper::fromListItem)
            .filter(Objects::nonNull)
            .toList();
    }

    public static Book fromAggregate(BookAggregate aggregate) {
        if (aggregate == null) {
            return null;
        }

        BookAggregate.ExternalIdentifiers identifiers = aggregate.getIdentifiers();
        SelectedImages images = resolvePreferredImages(identifiers);

        Book book = base(
            identifiers != null ? identifiers.getExternalId() : null,
            aggregate.getSlugBase(),
            aggregate.getTitle(),
            aggregate.getAuthors()
        );

        book.setDescription(aggregate.getDescription());
        book.setPublisher(aggregate.getPublisher());
        book.setPublishedDate(toDate(aggregate.getPublishedDate()));
        book.setLanguage(aggregate.getLanguage());
        book.setPageCount(aggregate.getPageCount());
        book.setCategories(aggregate.getCategories());
        book.setIsbn10(aggregate.getIsbn10());
        book.setIsbn13(aggregate.getIsbn13());

        if (identifiers != null) {
            book.setAverageRating(identifiers.getAverageRating());
            book.setRatingsCount(identifiers.getRatingsCount());
            book.setPreviewLink(identifiers.getPreviewLink());
            book.setInfoLink(identifiers.getInfoLink());
            book.setPurchaseLink(identifiers.getPurchaseLink());
            book.setWebReaderLink(identifiers.getWebReaderLink());
            book.setListPrice(identifiers.getListPrice());
            book.setCurrencyCode(identifiers.getCurrencyCode());
            book.setPdfAvailable(identifiers.getPdfAvailable());
            book.setEpubAvailable(identifiers.getEpubAvailable());
        }

        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(images.preferred(), images.fallback());
        setCoverImages(book, resolved, images.fallback());
        applyCoverMetadata(book, resolved.width(), resolved.height(), resolved.highResolution(), false);

        book.setEditionNumber(aggregate.getEditionNumber());
        // Task #6: editionGroupKey removed - replaced by work_clusters system
        book.setInPostgres(false);
        String source = identifiers != null ? identifiers.getSource() : null;
        if (ValidationUtils.hasText(source)) {
            book.setRetrievedFrom(source);
            book.setDataSource(source);
        } else {
            book.setRetrievedFrom("EXTERNAL_API");
        }
        return book;
    }

    private static Book base(String id,
                             String slug,
                             String title,
                             List<String> authors) {
        Book book = new Book();
        book.setId(id);
        book.setSlug(ValidationUtils.hasText(slug) ? slug : id);
        book.setTitle(title);
        book.setAuthors(authors);
        return book;
    }

    private static void setCoverImages(Book book,
                                       CoverUrlResolver.ResolvedCover resolved,
                                       String fallbackUrl) {
        if (resolved == null || !ValidationUtils.hasText(resolved.url())) {
            return;
        }
        String effectivePreferred = ValidationUtils.hasText(resolved.url()) ? resolved.url() : fallbackUrl;
        String effectiveFallback = ValidationUtils.hasText(fallbackUrl) ? fallbackUrl : effectivePreferred;

        if (resolved.fromS3()) {
            book.setS3ImagePath(resolved.s3Key());
        } else {
            book.setS3ImagePath(null);
        }

        CoverImages images = new CoverImages(
            effectivePreferred,
            effectiveFallback,
            UrlSourceDetector.detectSource(effectivePreferred)
        );
        book.setExternalImageUrl(effectiveFallback);
        book.setCoverImages(images);
    }

    private static void applyCoverMetadata(Book book,
                                           Integer width,
                                           Integer height,
                                           Boolean highResolution,
                                           boolean enforceSearchThreshold) {
        if (book == null) {
            return;
        }

        book.setCoverImageWidth(width);
        book.setCoverImageHeight(height);
        boolean derivedHighRes = highResolution != null
            ? highResolution
            : ImageDimensionUtils.isHighResolution(width, height);
        book.setIsCoverHighResolution(derivedHighRes);

        if (enforceSearchThreshold
            && !ImageDimensionUtils.meetsDisplayRequirements(width, height)) {
            suppressCover(book, "image-below-search-display-threshold");
        }
    }

    private static void suppressCover(Book book, String reason) {
        if (book == null) {
            return;
        }

        boolean hasCover = ValidationUtils.hasText(book.getExternalImageUrl())
            || ValidationUtils.hasText(book.getS3ImagePath());
        if (!hasCover) {
            return;
        }

        book.setExternalImageUrl(null);
        book.setS3ImagePath(null);
        book.setCoverImages(null);
        book.setCoverImageWidth(null);
        book.setCoverImageHeight(null);
        book.setIsCoverHighResolution(null);

        book.addQualifier(SUPPRESSED_FLAG_KEY, true);
        book.addQualifier(SUPPRESSED_REASON_KEY, reason);
        book.addQualifier(SUPPRESSED_MIN_HEIGHT_KEY, ImageDimensionUtils.MIN_SEARCH_RESULT_HEIGHT);
    }

    private static Date toDate(LocalDate value) {
        return value == null ? null : Date.from(value.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(source);
    }

    private static List<EditionInfo> toEditionInfo(List<EditionSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        List<EditionInfo> editions = new ArrayList<>(summaries.size());
        for (EditionSummary summary : summaries) {
            if (summary == null) {
                continue;
            }
            EditionInfo info = new EditionInfo();
            info.setGoogleBooksId(summary.id());
            info.setIdentifier(summary.slug());
            info.setEditionIsbn13(summary.isbn13());
            info.setPublishedDate(toDate(summary.publishedDate()));
            info.setCoverImageUrl(summary.coverUrl());
            editions.add(info);
        }
        return editions;
    }

    private static SelectedImages resolvePreferredImages(BookAggregate.ExternalIdentifiers identifiers) {
        if (identifiers == null || identifiers.getImageLinks() == null || identifiers.getImageLinks().isEmpty()) {
            return new SelectedImages(null, null);
        }

        Map<String, String> links = identifiers.getImageLinks();
        List<String> priorityOrder = List.of("extraLarge", "large", "medium", "small", "thumbnail", "smallThumbnail");

        String primary = null;
        String secondary = null;
        for (String key : priorityOrder) {
            String candidate = links.get(key);
            if (!ValidationUtils.hasText(candidate)) {
                continue;
            }
            if (primary == null) {
                primary = candidate;
            } else if (secondary == null) {
                secondary = candidate;
            }
        }

        if (!ValidationUtils.hasText(secondary)) {
            secondary = primary;
        }

        return new SelectedImages(primary, secondary);
    }

    private record SelectedImages(String preferred, String fallback) {
    }
}
