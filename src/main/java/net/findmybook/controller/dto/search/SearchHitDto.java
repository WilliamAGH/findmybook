package net.findmybook.controller.dto.search;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import net.findmybook.controller.dto.BookDto;

/**
 * Search row payload returned by the book search endpoint.
 *
 * @param book flattened book payload
 * @param matchType search match strategy label
 * @param relevanceScore optional rank score emitted by search pipelines
 */
public record SearchHitDto(@JsonUnwrapped BookDto book,
                           String matchType,
                           Double relevanceScore) {
}
