package net.findmybook.controller.dto;

import java.util.Date;

/** DTO describing a related edition of a book. */
public record EditionDto(String googleBooksId,
                         String type,
                         String identifier,
                         String isbn10,
                         String isbn13,
                         Date publishedDate,
                         String coverImageUrl) {
}
