package net.findmybook.controller.dto.search;

import java.util.List;

/**
 * Author search response payload.
 *
 * @param query normalized query text
 * @param limit effective result limit
 * @param results ordered author rows
 */
public record AuthorSearchResponse(String query,
                                   int limit,
                                   List<AuthorHitDto> results) {
}
