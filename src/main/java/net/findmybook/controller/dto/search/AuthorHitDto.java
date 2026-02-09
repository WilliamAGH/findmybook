package net.findmybook.controller.dto.search;

/**
 * Author search row payload.
 *
 * @param id stable author identifier
 * @param name display author name
 * @param bookCount number of associated books
 * @param relevanceScore computed author relevance score
 */
public record AuthorHitDto(String id,
                           String name,
                           long bookCount,
                           double relevanceScore) {
}
