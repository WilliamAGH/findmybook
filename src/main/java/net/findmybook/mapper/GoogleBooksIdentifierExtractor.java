package net.findmybook.mapper;

import tools.jackson.databind.JsonNode;
import net.findmybook.dto.BookAggregate;
import net.findmybook.util.ImageUrlEnhancer;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import static net.findmybook.mapper.GoogleBooksJsonSupport.getBooleanValue;
import static net.findmybook.mapper.GoogleBooksJsonSupport.getDoubleValue;
import static net.findmybook.mapper.GoogleBooksJsonSupport.getIntValue;
import static net.findmybook.mapper.GoogleBooksJsonSupport.getTextValue;

/**
 * Package-private extractor that builds {@link BookAggregate.ExternalIdentifiers}
 * from Google Books Volume JSON sections (volumeInfo, saleInfo, accessInfo).
 *
 * <p>Separated from {@link GoogleBooksMapper} to keep the mapper focused on
 * top-level orchestration while this class owns the identifier-building logic.</p>
 */
@Slf4j
final class GoogleBooksIdentifierExtractor {

    private static final String SOURCE_NAME = "GOOGLE_BOOKS";

    private GoogleBooksIdentifierExtractor() {
    }

    /**
     * Build external identifiers from all available Google Books metadata sections.
     */
    static BookAggregate.ExternalIdentifiers buildExternalIdentifiers(
            String externalId,
            JsonNode volumeInfo,
            JsonNode saleInfo,
            JsonNode accessInfo,
            String providerIsbn10,
            String providerIsbn13) {

        Map<String, String> imageLinks = extractImageLinks(volumeInfo);

        return BookAggregate.ExternalIdentifiers.builder()
            // Primary identifiers
            .source(SOURCE_NAME)
            .externalId(externalId)
            .providerIsbn10(providerIsbn10)
            .providerIsbn13(providerIsbn13)

            // Links
            .infoLink(getTextValue(volumeInfo, "infoLink"))
            .previewLink(getTextValue(volumeInfo, "previewLink"))
            .canonicalVolumeLink(getTextValue(volumeInfo, "canonicalVolumeLink"))
            .webReaderLink(accessInfo != null ? getTextValue(accessInfo, "webReaderLink") : null)

            // Ratings
            .averageRating(getDoubleValue(volumeInfo, "averageRating"))
            .ratingsCount(getIntValue(volumeInfo, "ratingsCount"))

            // Availability (from accessInfo)
            .embeddable(accessInfo != null ? getBooleanValue(accessInfo, "embeddable") : null)
            .publicDomain(accessInfo != null ? getBooleanValue(accessInfo, "publicDomain") : null)
            .viewability(accessInfo != null ? getTextValue(accessInfo, "viewability") : null)
            .textReadable(extractReadingMode(volumeInfo, "text"))
            .imageReadable(extractReadingMode(volumeInfo, "image"))
            .pdfAvailable(extractFormatAvailability(accessInfo, "pdf"))
            .epubAvailable(extractFormatAvailability(accessInfo, "epub"))
            .textToSpeechPermission(accessInfo != null ? getTextValue(accessInfo, "textToSpeechPermission") : null)

            // Content metadata
            .printType(getTextValue(volumeInfo, "printType"))
            .maturityRating(getTextValue(volumeInfo, "maturityRating"))
            .contentVersion(getTextValue(volumeInfo, "contentVersion"))

            // Sale info
            .isEbook(saleInfo != null ? getBooleanValue(saleInfo, "isEbook") : null)
            .saleability(saleInfo != null ? getTextValue(saleInfo, "saleability") : null)
            .countryCode(saleInfo != null ? getTextValue(saleInfo, "country") : null)
            .listPrice(extractPrice(saleInfo, "listPrice"))
            .retailPrice(extractPrice(saleInfo, "retailPrice"))
            .currencyCode(extractCurrency(saleInfo, "listPrice"))

            // Work identifiers
            .googleCanonicalId(extractGoogleCanonicalId(volumeInfo))

            // Image URLs
            .imageLinks(imageLinks)
            .build();
    }

    /**
     * Extract Google canonical ID from canonicalVolumeLink URL parameter.
     */
    private static String extractGoogleCanonicalId(JsonNode volumeInfo) {
        String link = getTextValue(volumeInfo, "canonicalVolumeLink");
        if (link == null) {
            return null;
        }

        int idIndex = link.indexOf("id=");
        if (idIndex != -1) {
            String idPart = link.substring(idIndex + 3);
            int ampIndex = idPart.indexOf('&');
            return ampIndex != -1 ? idPart.substring(0, ampIndex) : idPart;
        }

        return null;
    }

    /**
     * Extract image links map from volumeInfo.imageLinks.
     * Enhances URLs for better quality and filters out suspected non-cover images.
     *
     * @see net.findmybook.util.cover.CoverUrlValidator
     */
    private static Map<String, String> extractImageLinks(JsonNode volumeInfo) {
        Map<String, String> imageLinks = new HashMap<>();

        if (!volumeInfo.has("imageLinks")) {
            return imageLinks;
        }

        JsonNode imageLinksNode = volumeInfo.get("imageLinks");
        if (!imageLinksNode.isObject()) {
            return imageLinks;
        }

        String[] sizeKeys = {"smallThumbnail", "thumbnail", "small", "medium", "large", "extraLarge"};
        for (String key : sizeKeys) {
            if (imageLinksNode.has(key)) {
                String url = imageLinksNode.get(key).asText();
                if (url != null && !url.isBlank()) {
                    String enhancedUrl = ImageUrlEnhancer.enhanceGoogleImageUrl(url, key);

                    if (!net.findmybook.util.cover.CoverUrlValidator.isLikelyCoverImage(enhancedUrl)) {
                        log.debug("Filtered out suspected title page/interior image: {} for size {}", enhancedUrl, key);
                        continue;
                    }

                    imageLinks.put(key, enhancedUrl);
                }
            }
        }

        return imageLinks;
    }

    /**
     * Extract reading mode availability from volumeInfo.readingModes.
     */
    private static Boolean extractReadingMode(JsonNode volumeInfo, String mode) {
        if (!volumeInfo.has("readingModes")) {
            return null;
        }

        JsonNode readingModes = volumeInfo.get("readingModes");
        return readingModes.has(mode) ? readingModes.get(mode).asBoolean() : null;
    }

    /**
     * Extract format availability (PDF/EPUB) from accessInfo.
     */
    private static Boolean extractFormatAvailability(JsonNode accessInfo, String format) {
        if (accessInfo == null || !accessInfo.has(format)) {
            return null;
        }

        JsonNode formatNode = accessInfo.get(format);
        return formatNode.has("isAvailable") ? formatNode.get("isAvailable").asBoolean() : null;
    }

    /**
     * Extract price amount from a saleInfo price field (listPrice or retailPrice).
     */
    private static Double extractPrice(JsonNode saleInfo, String priceField) {
        if (saleInfo == null || !saleInfo.has(priceField)) {
            return null;
        }

        JsonNode priceNode = saleInfo.get(priceField);
        return priceNode.has("amount") ? priceNode.get("amount").asDouble() : null;
    }

    /**
     * Extract currency code from a saleInfo price field.
     */
    private static String extractCurrency(JsonNode saleInfo, String priceField) {
        if (saleInfo == null || !saleInfo.has(priceField)) {
            return null;
        }

        JsonNode priceNode = saleInfo.get(priceField);
        return priceNode.has("currencyCode") ? priceNode.get("currencyCode").asText() : null;
    }
}
