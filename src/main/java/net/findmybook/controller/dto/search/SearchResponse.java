package net.findmybook.controller.dto.search;

import java.util.List;

/**
 * API payload for offset-based search responses.
 *
 * @param query normalized search text
 * @param queryHash deterministic websocket key for realtime updates
 * @param startIndex zero-based absolute offset echoed from the request
 * @param maxResults effective page size for this response
 * @param totalResults total unique matches in the fetched result window
 * @param hasMore whether another page exists after this offset
 * @param nextStartIndex next zero-based absolute offset when hasMore is true
 * @param prefetchedCount number of extra rows already fetched beyond this page
 * @param orderBy normalized sorting key
 * @param coverSource normalized cover source filter
 * @param resolution normalized resolution filter
 * @param results current page rows
 */
public record SearchResponse(String query,
                             String queryHash,
                             int startIndex,
                             int maxResults,
                             int totalResults,
                             boolean hasMore,
                             int nextStartIndex,
                             int prefetchedCount,
                             String orderBy,
                             String coverSource,
                             String resolution,
                             List<SearchHitDto> results) {
}
