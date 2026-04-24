package net.findmybook.controller.dto;

import java.util.Date;

/** DTO containing publication metadata for a book. */
public record PublicationDto(Date publishedDate,
                             String language,
                             Integer pageCount,
                             String publisher) {
    /**
     * Returns an empty publication block for list surfaces that do not expose publication metadata.
     */
    public static PublicationDto empty() {
        return new PublicationDto(noPublishedDate(), noLanguage(), noPageCount(), noPublisher());
    }

    private static Date noPublishedDate() {
        return null;
    }

    private static String noLanguage() {
        return null;
    }

    private static Integer noPageCount() {
        return null;
    }

    private static String noPublisher() {
        return null;
    }
}
