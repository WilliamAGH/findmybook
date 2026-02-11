package net.findmybook.scheduler;

import jakarta.annotation.Nullable;

import java.time.LocalDate;

/**
 * Captures normalized NYT list context needed while processing each list entry.
 *
 * @param collectionId persisted collection identifier
 * @param listCode provider list code (for example, {@code hardcover-fiction})
 * @param listDisplayName normalized natural-language list label
 * @param listName provider list name when present
 * @param providerListId provider list identifier when present
 * @param updatedFrequency update cadence reported by NYT
 * @param bestsellersDate NYT bestsellers date for the overview payload
 * @param publishedDate NYT publication date for the list payload
 */
public record NytListContext(
    String collectionId,
    String listCode,
    String listDisplayName,
    @Nullable String listName,
    @Nullable String providerListId,
    @Nullable String updatedFrequency,
    @Nullable LocalDate bestsellersDate,
    @Nullable LocalDate publishedDate
) {}
