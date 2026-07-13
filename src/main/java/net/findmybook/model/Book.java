/**
 * Core book entity model containing all book metadata and cover image information
 *
 * Features:
 * - Represents books fetched from external sources like Google Books API
 * - Stores comprehensive book details including bibliographic data
 * - Tracks cover image metadata including resolution information
 * - Contains edition information for related formats of the same book
 */
package net.findmybook.model;

import net.findmybook.model.image.CoverImages;
import net.findmybook.util.ValidationUtils;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Book {

    @EqualsAndHashCode.Include
    private String id;
    private String slug;
    private String title;
    private List<String> authors;
    private String description;
    private String s3ImagePath;
    private String externalImageUrl;
    private String isbn10;
    private String isbn13;
    private Date publishedDate;
    private List<String> categories;
    private List<CollectionAssignment> collections = new ArrayList<>();
    private Double averageRating;
    private Integer ratingsCount;
    private String rawRatingsData;
    private Boolean hasRatings;
    private Integer pageCount;
    private String language;
    private String publisher;
    private String infoLink;
    private String previewLink;
    private String purchaseLink;
    private Double listPrice;
    private String currencyCode;
    private String webReaderLink;
    private Boolean pdfAvailable;
    private Boolean epubAvailable;
    private Integer coverImageWidth;
    private Integer coverImageHeight;
    private Boolean isCoverHighResolution;
    private Boolean isCoverGrayscale;
    private Double heightCm;
    private Double widthCm;
    private Double thicknessCm;
    private Double weightGrams;
    private CoverImages coverImages;
    private Integer editionNumber;
    private List<Edition> otherEditions;
    private String asin;
    private Map<String, Serializable> qualifiers;
    private List<String> cachedRecommendationIds;
    private transient String rawJsonResponse;

    // Retrieval metadata for development mode tracking
    private transient String retrievedFrom; // "POSTGRES", "S3", "GOOGLE_BOOKS_API", "OPEN_LIBRARY_API", etc.
    private transient String dataSource; // "GOOGLE_BOOKS", "NYT", "OPEN_LIBRARY", etc.
    private transient Boolean inPostgres; // Whether this book is currently persisted in Postgres

    public Book() {
        this.otherEditions = new ArrayList<>();
        this.qualifiers = new HashMap<>();
        this.cachedRecommendationIds = new ArrayList<>();
    }

    /**
     * Creates a book from provider metadata while preserving the same author invariants as setter-based construction.
     */
    public Book(String id,
                String title,
                List<String> authors,
                String description,
                String s3ImagePath,
                String externalImageUrl) {
        this.id = id;
        this.title = title;
        setAuthors(authors);
        this.description = description;
        this.s3ImagePath = s3ImagePath;
        this.externalImageUrl = externalImageUrl;
        this.otherEditions = new ArrayList<>();
        this.qualifiers = new HashMap<>();
        this.cachedRecommendationIds = new ArrayList<>();
    }

    /**
     * Removes malformed provider author values before they reach API projections.
     *
     * @param authors provider-supplied author labels
     */
    public void setAuthors(List<String> authors) {
        this.authors = sanitizeTextEntries(authors);
    }

    /**
     * Keeps provider categories safe for immutable API projections.
     *
     * @param categories provider-supplied category labels
     */
    public void setCategories(List<String> categories) {
        this.categories = sanitizeTextEntries(categories);
    }

    /**
     * Returns collection assignments without exposing mutable provider state.
     *
     * @return immutable collection assignments
     */
    public List<CollectionAssignment> getCollections() {
        if (collections == null || collections.isEmpty()) {
            return List.of();
        }
        return List.copyOf(collections);
    }

    /**
     * Removes null provider assignments so immutable collection projections remain safe.
     *
     * @param collections provider-supplied collection assignments
     */
    public void setCollections(List<CollectionAssignment> collections) {
        if (collections == null || collections.isEmpty()) {
            this.collections = new ArrayList<>();
            return;
        }
        this.collections = collections.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Adds one valid collection assignment while preserving the collection invariant.
     *
     * @param assignment collection membership to retain
     */
    public void addCollection(CollectionAssignment assignment) {
        if (assignment == null) {
            return;
        }
        if (this.collections == null) {
            this.collections = new ArrayList<>();
        }
        this.collections.add(assignment);
    }


    /**
     * Drops unusable qualifier entries and retains only serializable metadata so persistence and API
     * projections share one safe value contract.
     *
     * @param qualifiers provider-supplied qualifier metadata
     */
    public void setQualifiers(Map<String, ?> qualifiers) {
        this.qualifiers = new HashMap<>();
        if (qualifiers == null || qualifiers.isEmpty()) {
            return;
        }
        qualifiers.forEach((qualifierKey, qualifierValue) -> {
            if (qualifierKey != null && !qualifierKey.isBlank() && qualifierValue instanceof Serializable serializableValue) {
                this.qualifiers.put(qualifierKey, serializableValue);
            }
        });
    }

    /**
     * Adds a qualifier only when both its key and serializable value can be represented downstream.
     *
     * @param key qualifier key
     * @param value qualifier value
     */
    public void addQualifier(String key, Serializable value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        if (this.qualifiers == null) {
            this.qualifiers = new HashMap<>();
        }
        this.qualifiers.put(key, value);
    }

    public boolean hasQualifier(String key) {
        return this.qualifiers != null && this.qualifiers.containsKey(key);
    }

    /**
     * Normalizes cached recommendation identifiers before immutable DTO copies consume them.
     *
     * @param cachedRecommendationIds recommendation identifiers from persistence or a provider
     */
    public void setCachedRecommendationIds(List<String> cachedRecommendationIds) {
        this.cachedRecommendationIds = sanitizeTextEntries(cachedRecommendationIds);
    }

    /**
     * Adds distinct, nonblank recommendation identifiers without violating the cached-ID invariant.
     *
     * @param newRecommendationIds recommendation identifiers to merge
     */
    public void addRecommendationIds(List<String> newRecommendationIds) {
        if (newRecommendationIds == null || newRecommendationIds.isEmpty()) {
            return;
        }
        if (this.cachedRecommendationIds == null) {
            this.cachedRecommendationIds = new ArrayList<>();
        }
        for (String recommendationId : newRecommendationIds) {
            if (recommendationId == null) {
                continue;
            }
            String normalizedRecommendationId = recommendationId.trim();
            if (!normalizedRecommendationId.isEmpty() && !this.cachedRecommendationIds.contains(normalizedRecommendationId)) {
                this.cachedRecommendationIds.add(normalizedRecommendationId);
            }
        }
    }

    /**
     * Keeps edition projections free of null entries from provider payloads.
     *
     * @param otherEditions provider-supplied edition metadata
     */
    public void setOtherEditions(List<Edition> otherEditions) {
        if (otherEditions == null || otherEditions.isEmpty()) {
            this.otherEditions = new ArrayList<>();
            return;
        }
        this.otherEditions = otherEditions.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<String> sanitizeTextEntries(List<String> textEntries) {
        if (textEntries == null || textEntries.isEmpty()) {
            return new ArrayList<>();
        }
        return textEntries.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(textEntry -> !textEntry.isEmpty())
            .collect(Collectors.toCollection(ArrayList::new));
    }

    public void setPublisher(String publisher) {
        this.publisher = ValidationUtils.stripWrappingQuotes(publisher);
    }

    @Override
    public String toString() {
        return "Book{" +
            "id='" + id + '\'' +
            ", title='" + title + '\'' +
            ", authors=" + authors +
            ", otherEditionsCount=" + (otherEditions != null ? otherEditions.size() : 0) +
            '}';
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CollectionAssignment {
        private String collectionId;
        private String name;
        private String collectionType;
        private Integer rank;
        private String source;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edition {
        private String googleBooksId;
        private String type;
        private String identifier;
        private String editionIsbn10;
        private String editionIsbn13;
        private Date publishedDate;
        private String coverImageUrl;
    }
}
