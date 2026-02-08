package net.findmybook.controller.dto;

/** DTO capturing cover metadata for API clients. */
public record CoverDto(String s3ImagePath,
                       String externalImageUrl,
                       Integer width,
                       Integer height,
                       Boolean highResolution,
                       String preferredUrl,
                       String fallbackUrl,
                       String source) {
}
