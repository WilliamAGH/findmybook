package net.findmybook.controller.dto;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import net.findmybook.dto.BookCard;
import net.findmybook.dto.BookDetail;
import net.findmybook.dto.BookListItem;
import net.findmybook.dto.EditionSummary;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImages;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.SlugGenerator;
import net.findmybook.util.cover.CoverUrlValidator;
import net.findmybook.util.cover.CoverUrlResolver;
import net.findmybook.util.cover.UrlSourceDetector;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * Shared mapper that transforms domain {@link Book} objects into API-facing DTOs.
 * Centralizing this logic lets controller layers adopt Postgres-first data
 * without rewriting mapping code in multiple places.
 */
public final class BookDtoMapper {

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<\\s*/?\\s*[a-zA-Z][^>]*>");
    private static final Pattern MARKDOWN_PATTERN = Pattern.compile(
        "(?m)^(#{1,6}\\s+.+|\\s*[-*+]\\s+.+|\\s*\\d+\\.\\s+.+|\\s*>\\s+.+)|(```|`[^`]+`|\\*\\*[^*]+\\*\\*|\\[[^\\]]+\\]\\([^\\)]+\\))"
    );
    private static final Pattern BR_TAG_PATTERN = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern EMPTY_BOLD_BR_PATTERN = Pattern.compile("(?i)<b>\\s*(<br\\s*/?>)\\s*</b>");
    private static final Pattern BOLD_THEN_BULLET_PATTERN = Pattern.compile("(</b>)\\s*([●•])", Pattern.CASE_INSENSITIVE);
    private static final String BULLET_CHARS = "●•";
    private static final Parser MARKDOWN_PARSER = buildMarkdownParser();
    private static final HtmlRenderer MARKDOWN_RENDERER = buildMarkdownRenderer();
    private static final Cleaner DESCRIPTION_HTML_CLEANER = new Cleaner(buildDescriptionSafelist());

    private BookDtoMapper() {
    }

    public static BookDto toDto(Book book) {
        if (book == null) {
            return null;
        }

        PublicationDto publication = new PublicationDto(
                safeCopy(book.getPublishedDate()),
                book.getLanguage(),
                book.getPageCount(),
                book.getPublisher()
        );

        CoverDto cover = buildCover(book);

        List<AuthorDto> authors = mapAuthors(book);
        List<String> categories = book.getCategories() == null ? List.of() : List.copyOf(book.getCategories());
        List<TagDto> tags = mapTags(book);
        List<CollectionDto> collections = mapCollections(book);
        List<EditionDto> editions = mapEditions(book);
        List<String> recommendationIds = book.getCachedRecommendationIds() == null
                ? List.of()
                : List.copyOf(book.getCachedRecommendationIds());
        BookDto.DescriptionContent descriptionContent = formatDescription(book.getDescription());

        String slug = resolveSlug(book);

        return new BookDto(
                book.getId(),
                slug,
                book.getTitle(),
                book.getDescription(),
                publication,
                authors,
                categories,
                collections,
                tags,
                cover,
                editions,
                recommendationIds,
                book.getQualifiers() == null ? Map.of() : Map.copyOf(book.getQualifiers()),
                descriptionContent,
                null
        );
    }

    public static BookDto fromDetail(BookDetail detail) {
        return fromDetail(detail, Map.of());
    }

    public static BookDto fromDetail(BookDetail detail, Map<String, Object> extras) {
        if (detail == null) {
            return null;
        }

        PublicationDto publication = new PublicationDto(
            toDate(detail.publishedDate()),
            detail.language(),
            detail.pageCount(),
            detail.publisher()
        );

        String alternateCandidate = firstNonBlank(detail.thumbnailUrl(), detail.coverUrl());
        String fallbackCandidate = firstNonBlank(detail.thumbnailUrl(), detail.coverUrl());

        CoverDto cover = buildCoverDto(new CoverCandidates(
            detail.coverUrl(), alternateCandidate, fallbackCandidate,
            detail.coverWidth(), detail.coverHeight(), detail.coverHighResolution(),
            detail.dataSource()
        ));

        List<AuthorDto> authors = toAuthorDtos(detail.authors());
        List<CollectionDto> collections = List.of();
        List<TagDto> tags = toTagDtos(detail.tags());
        List<EditionDto> editions = detail.editions().stream()
            .map(BookDtoMapper::toEditionDto)
            .toList();
        BookDto.DescriptionContent descriptionContent = formatDescription(detail.description());

        return new BookDto(
            detail.id(),
            detail.slug(),
            detail.title(),
            detail.description(),
            publication,
            authors,
            detail.categories(),
            collections,
            tags,
            cover,
            editions,
            List.of(),
            extras == null ? Map.of() : Map.copyOf(extras),
            descriptionContent,
            null
        );
    }

    public static BookDto fromCard(BookCard card) {
        return fromCard(card, Map.of());
    }

    public static BookDto fromCard(BookCard card, Map<String, Object> extras) {
        if (card == null) {
            return null;
        }

        CoverDto cover = buildCoverDto(new CoverCandidates(
            card.coverUrl(), card.fallbackCoverUrl(), card.fallbackCoverUrl(),
            null, null, null, null
        ));

        PublicationDto publication = new PublicationDto(null, null, null, null);

        return new BookDto(
            card.id(),
            card.slug(),
            card.title(),
            null,
            publication,
            toAuthorDtos(card.authors()),
            List.of(),
            List.of(),
            toTagDtos(card.tags()),
            cover,
            List.of(),
            List.of(),
            extras == null ? Map.of() : Map.copyOf(extras),
            formatDescription(null),
            null
        );
    }

    public static BookDto fromListItem(BookListItem item) {
        return fromListItem(item, Map.of());
    }

    public static BookDto fromListItem(BookListItem item, Map<String, Object> extras) {
        if (item == null) {
            return null;
        }

        PublicationDto publication = new PublicationDto(null, null, null, null);

        CoverDto cover = buildCoverDto(new CoverCandidates(
            item.coverUrl(), item.coverFallbackUrl(), item.coverFallbackUrl(),
            item.coverWidth(), item.coverHeight(), item.coverHighResolution(), null
        ));

        return new BookDto(
            item.id(),
            item.slug(),
            item.title(),
            item.description(),
            publication,
            toAuthorDtos(item.authors()),
            item.categories(),
            List.of(),
            toTagDtos(item.tags()),
            cover,
            List.of(),
            List.of(),
            extras == null ? Map.of() : Map.copyOf(extras),
            formatDescription(item.description()),
            null
        );
    }

    private static BookDto.DescriptionContent formatDescription(String rawDescription) {
        if (!StringUtils.hasText(rawDescription)) {
            return new BookDto.DescriptionContent(rawDescription, BookDto.DescriptionFormat.UNKNOWN, "", "");
        }

        String normalizedDescription = normalizeLineEndings(rawDescription).trim();
        if (!StringUtils.hasText(normalizedDescription)) {
            return new BookDto.DescriptionContent(rawDescription, BookDto.DescriptionFormat.UNKNOWN, "", "");
        }

        BookDto.DescriptionFormat detectedFormat = detectSourceFormat(normalizedDescription);
        String renderedHtml = switch (detectedFormat) {
            case HTML -> normalizeHtmlStructure(normalizedDescription);
            case MARKDOWN -> renderMarkdownAsHtml(normalizedDescription);
            case PLAIN_TEXT, UNKNOWN -> renderPlainTextAsHtml(normalizedDescription);
        };

        String sanitizedHtml = sanitizeDescriptionHtml(renderedHtml);
        String plainText = extractPlainText(sanitizedHtml);
        return new BookDto.DescriptionContent(rawDescription, detectedFormat, sanitizedHtml, plainText);
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static BookDto.DescriptionFormat detectSourceFormat(String normalizedDescription) {
        if (HTML_TAG_PATTERN.matcher(normalizedDescription).find()) {
            return BookDto.DescriptionFormat.HTML;
        }
        if (MARKDOWN_PATTERN.matcher(normalizedDescription).find()) {
            return BookDto.DescriptionFormat.MARKDOWN;
        }
        return BookDto.DescriptionFormat.PLAIN_TEXT;
    }

    private static String renderMarkdownAsHtml(String normalizedDescription) {
        Node parsedMarkdown = MARKDOWN_PARSER.parse(normalizedDescription);
        return MARKDOWN_RENDERER.render(parsedMarkdown);
    }

    private static String renderPlainTextAsHtml(String normalizedDescription) {
        String[] paragraphs = normalizedDescription.split("\\n\\s*\\n+");
        StringBuilder htmlBuilder = new StringBuilder(normalizedDescription.length() + 32);

        for (String paragraph : paragraphs) {
            String trimmedParagraph = paragraph.strip();
            if (!StringUtils.hasText(trimmedParagraph)) {
                continue;
            }

            String[] lines = trimmedParagraph.split("\\n", -1);
            StringBuilder lineHtmlBuilder = new StringBuilder(trimmedParagraph.length() + 16);
            for (String line : lines) {
                String escapedLine = Jsoup.clean(line, Safelist.none());
                if (!lineHtmlBuilder.isEmpty()) {
                    lineHtmlBuilder.append("<br />");
                }
                lineHtmlBuilder.append(escapedLine);
            }

            if (!htmlBuilder.isEmpty()) {
                htmlBuilder.append('\n');
            }
            htmlBuilder.append("<p>")
                .append(lineHtmlBuilder)
                .append("</p>");
        }

        return htmlBuilder.toString();
    }

    /**
     * Normalizes ad-hoc HTML formatting from providers like Google Books into semantic HTML.
     * Converts inline bullet characters ({@code ●}, {@code •}) separated by {@code <br>} tags
     * into proper {@code <ul>/<li>} lists and ensures bold headings are followed by a line break
     * before bullet content.
     */
    private static String normalizeHtmlStructure(String html) {
        if (!StringUtils.hasText(html)) {
            return html;
        }

        // Strip empty bold wrappers around <br>: <b><br></b> → <br>
        String result = EMPTY_BOLD_BR_PATTERN.matcher(html).replaceAll("$1");

        // Insert <br> between closing </b> and immediately following bullet character
        result = BOLD_THEN_BULLET_PATTERN.matcher(result).replaceAll("$1<br>$2");

        return convertInlineBulletsToList(result);
    }

    /**
     * Splits the HTML by {@code <br>} tags, identifies consecutive segments beginning with
     * a bullet character ({@code ●} or {@code •}), and wraps each run in
     * {@code <ul><li>…</li></ul>} while re-joining non-bullet segments with {@code <br>}.
     */
    private static String convertInlineBulletsToList(String html) {
        String[] segments = BR_TAG_PATTERN.split(html, -1);
        StringBuilder result = new StringBuilder(html.length() + 64);
        boolean inList = false;

        for (int i = 0; i < segments.length; i++) {
            String trimmed = segments[i].strip();
            boolean isBullet = !trimmed.isEmpty()
                && BULLET_CHARS.indexOf(trimmed.charAt(0)) >= 0;

            if (isBullet) {
                if (!inList) {
                    result.append("<ul>");
                    inList = true;
                }
                String bulletContent = trimmed.substring(1).strip();
                result.append("<li>").append(bulletContent).append("</li>");
            } else {
                if (inList) {
                    result.append("</ul>");
                    inList = false;
                }
                if (i > 0) {
                    result.append("<br>");
                }
                result.append(segments[i]);
            }
        }

        if (inList) {
            result.append("</ul>");
        }

        return result.toString();
    }

    private static String sanitizeDescriptionHtml(String candidateHtml) {
        if (!StringUtils.hasText(candidateHtml)) {
            return "";
        }

        Document dirtyDocument = Jsoup.parseBodyFragment(candidateHtml);
        Document cleanDocument = DESCRIPTION_HTML_CLEANER.clean(dirtyDocument);
        cleanDocument.outputSettings().prettyPrint(false);
        return cleanDocument.body().html().trim();
    }

    private static String extractPlainText(String sanitizedHtml) {
        if (!StringUtils.hasText(sanitizedHtml)) {
            return "";
        }

        Document parsedDocument = Jsoup.parseBodyFragment(sanitizedHtml);
        parsedDocument.outputSettings().prettyPrint(false);

        parsedDocument.select("br").forEach(element -> element.after("\\n"));
        parsedDocument.select("p,div,section,article,ul,ol,li,blockquote,pre,h1,h2,h3,h4,h5,h6")
            .forEach(element -> element.prepend("\\n"));

        String extracted = parsedDocument.text().replace("\\n", "\n");
        return extracted.replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static Parser buildMarkdownParser() {
        MutableDataSet parserOptions = new MutableDataSet()
            .set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create(),
                AutolinkExtension.create()
            ));
        return Parser.builder(parserOptions).build();
    }

    private static HtmlRenderer buildMarkdownRenderer() {
        MutableDataSet rendererOptions = new MutableDataSet()
            .set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create(),
                AutolinkExtension.create()
            ))
            .set(HtmlRenderer.ESCAPE_HTML, true)
            .set(HtmlRenderer.SUPPRESS_HTML, true)
            .set(HtmlRenderer.SOFT_BREAK, "<br />\n")
            .set(HtmlRenderer.HARD_BREAK, "<br />\n");
        return HtmlRenderer.builder(rendererOptions).build();
    }

    private static Safelist buildDescriptionSafelist() {
        return Safelist.none()
            .addTags(
                "p", "br", "strong", "b", "em", "i", "u",
                "ul", "ol", "li", "blockquote", "code", "pre",
                "h1", "h2", "h3", "h4", "h5", "h6", "a"
            )
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto");
    }

    private static List<AuthorDto> toAuthorDtos(List<String> authors) {
        if (authors == null || authors.isEmpty()) {
            return List.of();
        }
        return authors.stream()
            .filter(Objects::nonNull)
            .map(name -> new AuthorDto(null, name))
            .toList();
    }

    private static List<TagDto> toTagDtos(Map<String, Object> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.entrySet().stream()
            .map(entry -> new TagDto(entry.getKey(), toAttributeMap(entry.getValue())))
            .toList();
    }

    private static EditionDto toEditionDto(EditionSummary summary) {
        if (summary == null) {
            return null;
        }
        Date published = toDate(summary.publishedDate());
        return new EditionDto(
            summary.id(),
            summary.slug(),
            summary.title(),
            null,
            summary.isbn13(),
            published,
            summary.coverUrl()
        );
    }

    private static Date toDate(LocalDate date) {
        return date == null ? null : Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static String resolveSlug(Book book) {
        if (book.getSlug() != null && !book.getSlug().isBlank()) {
            return book.getSlug();
        }
        return SlugGenerator.generateBookSlug(book.getTitle(), book.getAuthors());
    }

    private static CoverDto buildCover(Book book) {
        if (book == null) {
            return null;
        }
        CoverImages coverImages = book.getCoverImages();
        String preferredCandidate = coverImages != null && StringUtils.hasText(coverImages.getPreferredUrl())
            ? coverImages.getPreferredUrl()
            : null;
        String fallbackCandidate = coverImages != null && StringUtils.hasText(coverImages.getFallbackUrl())
            ? coverImages.getFallbackUrl()
            : book.getExternalImageUrl();
        String alternateCandidate = firstNonBlank(preferredCandidate, book.getExternalImageUrl(), fallbackCandidate);
        String primaryCandidate = StringUtils.hasText(book.getS3ImagePath())
            ? book.getS3ImagePath()
            : alternateCandidate;
        String declaredSource = coverImages != null && coverImages.getSource() != null
            ? coverImages.getSource().name()
            : null;
        return buildCoverDto(new CoverCandidates(
            primaryCandidate, alternateCandidate, fallbackCandidate,
            book.getCoverImageWidth(), book.getCoverImageHeight(),
            book.getIsCoverHighResolution(), declaredSource
        ));
    }

    /**
     * Input parameters for cover URL resolution.
     */
    private record CoverCandidates(
        String primary,
        String alternate,
        String fallback,
        Integer width,
        Integer height,
        Boolean highResolution,
        String declaredSource
    ) {}

    private static CoverDto buildCoverDto(CoverCandidates candidates) {
        String placeholder = ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH;
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            candidates.primary(), candidates.alternate(),
            candidates.width(), candidates.height(), candidates.highResolution()
        );

        String fallbackUrl = resolveFallbackUrl(candidates, placeholder);
        boolean fallbackLikely = CoverUrlValidator.isLikelyCoverImage(fallbackUrl);
        boolean preferredLikely = CoverUrlValidator.isLikelyCoverImage(resolved.url());
        String preferredUrl = StringUtils.hasText(resolved.url()) ? resolved.url() : fallbackUrl;

        String s3Key = resolved.fromS3() ? resolved.s3Key() : null;
        Integer effectiveWidth = resolved.width();
        Integer effectiveHeight = resolved.height();
        boolean effectiveHighResolution = resolved.highResolution();
        String externalUrl = resolved.fromS3()
            ? firstNonBlank(candidates.fallback(), candidates.alternate())
            : preferredUrl;

        if (preferredLikely) {
            externalUrl = resolveExternalUrlWhenPreferred(externalUrl, fallbackUrl, placeholder, resolved.fromS3());
        } else {
            s3Key = null;
            if (fallbackLikely && !placeholder.equals(fallbackUrl)) {
                preferredUrl = fallbackUrl;
                externalUrl = fallbackUrl;
            } else {
                preferredUrl = placeholder;
                externalUrl = null;
                effectiveWidth = null;
                effectiveHeight = null;
                effectiveHighResolution = false;
            }
        }

        if (externalUrl != null && externalUrl.contains("placeholder-book-cover.svg")) {
            externalUrl = null;
        }

        String source = resolveSourceLabel(candidates.declaredSource(), resolved, preferredUrl, preferredLikely, fallbackUrl, placeholder);

        return new CoverDto(s3Key, externalUrl, effectiveWidth, effectiveHeight,
            effectiveHighResolution, preferredUrl, fallbackUrl, source);
    }

    private static String resolveFallbackUrl(CoverCandidates candidates, String placeholder) {
        String url = firstNonBlank(candidates.fallback(), candidates.alternate(), candidates.primary(), placeholder);
        return CoverUrlValidator.isLikelyCoverImage(url) ? url : placeholder;
    }

    private static String resolveExternalUrlWhenPreferred(String externalUrl, String fallbackUrl,
                                                          String placeholder, boolean fromS3) {
        if (placeholder.equals(fallbackUrl)) {
            return null;
        }
        return fromS3 ? externalUrl : externalUrl;
    }

    private static String resolveSourceLabel(String declaredSource, CoverUrlResolver.ResolvedCover resolved,
                                             String preferredUrl, boolean preferredLikely,
                                             String fallbackUrl, String placeholder) {
        if (preferredUrl.contains("placeholder-book-cover.svg") || placeholder.equals(fallbackUrl)) {
            return CoverImageSource.NONE.name();
        }
        if (StringUtils.hasText(declaredSource)) {
            return declaredSource;
        }
        if (resolved.fromS3() && preferredLikely) {
            return "S3_CACHE";
        }
        CoverImageSource detected = UrlSourceDetector.detectSource(preferredUrl);
        return detected != null ? detected.name() : CoverImageSource.UNDEFINED.name();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static List<AuthorDto> mapAuthors(Book book) {
        if (book.getAuthors() == null || book.getAuthors().isEmpty()) {
            return List.of();
        }
        return book.getAuthors().stream()
                .filter(Objects::nonNull)
                .map(name -> new AuthorDto(null, name))
                .toList();
    }

    private static List<TagDto> mapTags(Book book) {
        if (book.getQualifiers() == null || book.getQualifiers().isEmpty()) {
            return List.of();
        }
        return book.getQualifiers().entrySet().stream()
                .map(entry -> new TagDto(entry.getKey(), toAttributeMap(entry.getValue())))
                .toList();
    }

    private static List<CollectionDto> mapCollections(Book book) {
        List<Book.CollectionAssignment> assignments = book.getCollections();
        if (assignments == null || assignments.isEmpty()) {
            return List.of();
        }
        return assignments.stream()
                .map(assignment -> new CollectionDto(
                        assignment.getCollectionId(),
                        assignment.getName(),
                        assignment.getCollectionType(),
                        assignment.getRank(),
                        assignment.getSource()
                ))
                .toList();
    }

    private static Map<String, Object> toAttributeMap(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return mapValue.entrySet().stream()
                    .filter(e -> e.getKey() != null)
                    .collect(Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue));
        }
        return Map.of("value", value);
    }

    private static List<EditionDto> mapEditions(Book book) {
        if (book.getOtherEditions() == null || book.getOtherEditions().isEmpty()) {
            return List.of();
        }
        return book.getOtherEditions().stream()
                .map(edition -> new EditionDto(
                        edition.getGoogleBooksId(),
                        edition.getType(),
                        edition.getIdentifier(),
                        edition.getEditionIsbn10(),
                        edition.getEditionIsbn13(),
                        safeCopy(edition.getPublishedDate()),
                        edition.getCoverImageUrl()
                ))
                .toList();
    }

    private static Date safeCopy(Date date) {
        return date == null ? null : new Date(date.getTime());
    }
}
