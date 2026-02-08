package net.findmybook.controller.dto;

import net.findmybook.model.Book;
import net.findmybook.model.Book.EditionInfo;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.CoverImages;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.cover.CoverUrlResolver;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BookDtoMapperTest {

    @Test
    void toDto_mapsBasicFields() {
        Book book = new Book();
        book.setId("fixture-id");
        book.setTitle("Fixture Title");
        book.setDescription("Fixture Description");
        book.setAuthors(List.of("Author One"));
        book.setCategories(List.of("Fiction"));
        book.setPublishedDate(new Date(1726700000000L));
        book.setLanguage("en");
        book.setPageCount(352);
        book.setPublisher("Fixture Publisher");
        book.setS3ImagePath(null);
        book.setExternalImageUrl("https://example.test/fixture.jpg");
        book.setCoverImageWidth(600);
        book.setCoverImageHeight(900);
        book.setIsCoverHighResolution(true);
        book.setQualifiers(Map.of("nytBestseller", Map.of("year", 2024)));
        book.setCachedRecommendationIds(List.of("rec-1", "rec-2"));

        CoverImages coverImages = new CoverImages("https://cdn.test/preferred.jpg", "https://cdn.test/fallback.jpg", CoverImageSource.GOOGLE_BOOKS);
        book.setCoverImages(coverImages);

        EditionInfo editionInfo = new EditionInfo("gb-123", "HARDCOVER", "Identifier", "1234567890", "9781234567890", new Date(1726000000000L), "https://cdn.test/hardcover.jpg");
        book.setOtherEditions(List.of(editionInfo));

        BookDto dto = BookDtoMapper.toDto(book);

        assertThat(dto.id()).isEqualTo("fixture-id");
        assertThat(dto.title()).isEqualTo("Fixture Title");
        assertThat(dto.publication().language()).isEqualTo("en");
        assertThat(dto.authors()).extracting(AuthorDto::name).containsExactly("Author One");
        assertThat(dto.categories()).containsExactly("Fiction");
        assertThat(dto.cover().preferredUrl()).isEqualTo(coverImages.getPreferredUrl());
        assertThat(dto.cover().source()).isEqualTo(ApplicationConstants.Provider.GOOGLE_BOOKS);
        assertThat(dto.tags()).hasSize(1);
        assertThat(dto.editions()).hasSize(1);
        assertThat(dto.recommendationIds()).containsExactly("rec-1", "rec-2");
        assertThat(dto.descriptionContent().raw()).isEqualTo("Fixture Description");
        assertThat(dto.descriptionContent().format()).isEqualTo(BookDto.DescriptionFormat.PLAIN_TEXT);
        assertThat(dto.descriptionContent().html()).isEqualTo("<p>Fixture Description</p>");
        assertThat(dto.descriptionContent().text()).isEqualTo("Fixture Description");
    }

    @Test
    void toDto_stripsWrappingQuotesFromPublisher() {
        Book book = new Book();
        book.setId("id-sanitized");
        book.setTitle("Quoted Publisher Book");
        book.setAuthors(List.of("Author"));
        book.setPublisher("\"O'Reilly Media, Inc.\"");

        BookDto dto = BookDtoMapper.toDto(book);

        assertThat(dto.publication().publisher()).isEqualTo("O'Reilly Media, Inc.");
    }

    @Test
    void toDto_replacesInvalidFallbackWithPlaceholder() {
        Book book = new Book();
        book.setId("bad-fallback");
        book.setTitle("Bad Fallback Cover");
        book.setS3ImagePath("images/book-covers/bad.jpg");
        book.setExternalImageUrl("https://book-finder.example.invalid/images/book-covers/bad.jpg");
        book.setCoverImageWidth(400);
        book.setCoverImageHeight(600);

        CoverImages images = new CoverImages(
            "https://book-finder.example.invalid/images/book-covers/bad.jpg",
            "https://books.google.com/books/content?id=BAD&printsec=frontcover"
        );
        book.setCoverImages(images);

        BookDto dto = BookDtoMapper.toDto(book);

        assertThat(dto.cover().fallbackUrl()).isEqualTo(ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH);
        assertThat(dto.cover().source()).isEqualTo(CoverImageSource.NONE.name());
        assertThat(dto.cover().highResolution()).isFalse();
    }

    @Test
    void toDto_doesNotEmitCdnUrlForPlaceholderS3Key() {
        CoverUrlResolver.setCdnBase("https://cdn.test/");
        try {
            Book book = new Book();
            book.setId("placeholder-s3");
            book.setTitle("Placeholder S3");
            book.setS3ImagePath("images/placeholder-book-cover.svg");

            BookDto dto = BookDtoMapper.toDto(book);

            assertThat(dto.cover().preferredUrl()).isEqualTo(ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH);
            assertThat(dto.cover().s3ImagePath()).isNull();
            assertThat(dto.cover().source()).isEqualTo(CoverImageSource.NONE.name());
        } finally {
            CoverUrlResolver.setCdnBase(null);
        }
    }

    @Test
    void should_SanitizeUnsafeHtml_When_DescriptionContainsScriptTag() {
        Book book = new Book();
        book.setId("unsafe-html");
        book.setTitle("Unsafe HTML");
        book.setDescription("<p>Hello</p><script>alert('xss')</script><ul><li>One</li></ul>");

        BookDto dto = BookDtoMapper.toDto(book);

        assertThat(dto.descriptionContent().format()).isEqualTo(BookDto.DescriptionFormat.HTML);
        assertThat(dto.descriptionContent().html()).contains("<p>Hello</p>");
        assertThat(dto.descriptionContent().html()).contains("<ul><li>One</li></ul>");
        assertThat(dto.descriptionContent().html()).doesNotContain("<script");
        assertThat(dto.descriptionContent().text()).contains("Hello");
        assertThat(dto.descriptionContent().text()).contains("One");
    }

    @Test
    void should_RenderMarkdownAsSanitizedHtml_When_DescriptionUsesMarkdownSyntax() {
        Book book = new Book();
        book.setId("markdown-description");
        book.setTitle("Markdown Description");
        book.setDescription("## Key Features\n- Item one\n- Item two\n**Bold text**");

        BookDto dto = BookDtoMapper.toDto(book);

        assertThat(dto.descriptionContent().format()).isEqualTo(BookDto.DescriptionFormat.MARKDOWN);
        assertThat(dto.descriptionContent().html()).contains("<h2>Key Features</h2>");
        assertThat(dto.descriptionContent().html()).contains("<ul>");
        assertThat(dto.descriptionContent().html()).contains("<li>Item one</li>");
        assertThat(dto.descriptionContent().html()).contains("<strong>Bold text</strong>");
        assertThat(dto.descriptionContent().text()).contains("Key Features");
        assertThat(dto.descriptionContent().text()).contains("Item one");
    }

    @Test
    void should_PreserveLineBreaks_When_DescriptionIsPlainTextParagraphs() {
        Book book = new Book();
        book.setId("plain-text-description");
        book.setTitle("Plain Text Description");
        book.setDescription("First line\nSecond line\n\nThird paragraph");

        BookDto dto = BookDtoMapper.toDto(book);

        assertThat(dto.descriptionContent().format()).isEqualTo(BookDto.DescriptionFormat.PLAIN_TEXT);
        assertThat(dto.descriptionContent().html()).contains("<p>First line<br");
        assertThat(dto.descriptionContent().html()).contains("Second line</p>");
        assertThat(dto.descriptionContent().html()).contains("<p>Third paragraph</p>");
        assertThat(dto.descriptionContent().text()).contains("First line");
        assertThat(dto.descriptionContent().text()).contains("Second line");
        assertThat(dto.descriptionContent().text()).contains("Third paragraph");
    }

    @Test
    void should_ConvertInlineBulletsToList_When_HtmlContainsUnicodeBullets() {
        Book book = new Book();
        book.setId("inline-bullets");
        book.setTitle("Inline Bullets");
        book.setDescription(
            "<b>Key Features</b>● Feature one<br>● Feature two<br>● Feature three"
        );

        BookDto dto = BookDtoMapper.toDto(book);

        assertThat(dto.descriptionContent().format()).isEqualTo(BookDto.DescriptionFormat.HTML);
        assertThat(dto.descriptionContent().html()).contains("<ul>");
        assertThat(dto.descriptionContent().html()).contains("<li>Feature one</li>");
        assertThat(dto.descriptionContent().html()).contains("<li>Feature two</li>");
        assertThat(dto.descriptionContent().html()).contains("<li>Feature three</li>");
        assertThat(dto.descriptionContent().html()).doesNotContain("●");
    }

    @Test
    void should_PreserveNonBulletHtml_When_DescriptionHasNoBullets() {
        Book book = new Book();
        book.setId("no-bullets");
        book.setTitle("No Bullets");
        book.setDescription("<b>Title</b><br>Some description text<br><br>Another paragraph");

        BookDto dto = BookDtoMapper.toDto(book);

        assertThat(dto.descriptionContent().format()).isEqualTo(BookDto.DescriptionFormat.HTML);
        assertThat(dto.descriptionContent().html()).contains("<b>Title</b>");
        assertThat(dto.descriptionContent().html()).doesNotContain("<ul>");
        assertThat(dto.descriptionContent().html()).doesNotContain("<li>");
    }

    @Test
    void should_NormalizeEmptyBoldBrWrappers_When_BoldWrapsBreakTag() {
        Book book = new Book();
        book.setId("bold-br");
        book.setTitle("Bold BR");
        book.setDescription(
            "● Item one<br>● Item two<b><br></b><b>Next Section</b>"
        );

        BookDto dto = BookDtoMapper.toDto(book);

        assertThat(dto.descriptionContent().html()).contains("<li>Item one</li>");
        assertThat(dto.descriptionContent().html()).contains("<li>Item two</li>");
        assertThat(dto.descriptionContent().html()).contains("</ul>");
        assertThat(dto.descriptionContent().html()).contains("<b>Next Section</b>");
    }

    @Test
    void should_HandleDotBullets_When_HtmlContainsMidDotCharacter() {
        Book book = new Book();
        book.setId("dot-bullets");
        book.setTitle("Dot Bullets");
        book.setDescription("<b>Topics</b><br>• Topic one<br>• Topic two");

        BookDto dto = BookDtoMapper.toDto(book);

        assertThat(dto.descriptionContent().html()).contains("<ul>");
        assertThat(dto.descriptionContent().html()).contains("<li>Topic one</li>");
        assertThat(dto.descriptionContent().html()).contains("<li>Topic two</li>");
        assertThat(dto.descriptionContent().html()).doesNotContain("•");
    }

    @Test
    void should_InsertBreakBetweenConsecutiveBoldBlocks_When_NoBreakExists() {
        Book book = new Book();
        book.setId("consecutive-bold");
        book.setTitle("Consecutive Bold");
        book.setDescription("<b>Section One</b><b>Section Two</b> continues here");

        BookDto dto = BookDtoMapper.toDto(book);

        String html = dto.descriptionContent().html();
        assertThat(html).contains("<b>Section One</b>");
        assertThat(html).contains("<b>Section Two</b>");
        // Verify a <br> was inserted between the two bold blocks
        assertThat(html).doesNotContain("</b><b>");
    }

    @Test
    void should_InsertBreakAfterBold_When_FollowedByDigit() {
        Book book = new Book();
        book.setId("bold-then-digit");
        book.setTitle("Bold Then Digit");
        book.setDescription("<b>Table of Contents</b>1. First Chapter<br>2. Second Chapter");

        BookDto dto = BookDtoMapper.toDto(book);

        String html = dto.descriptionContent().html();
        assertThat(html).contains("<b>Table of Contents</b>");
        // Ensure <br> is between heading and numbered list
        assertThat(html).doesNotContain("</b>1.");
        assertThat(html).contains("1. First Chapter");
    }

    @Test
    void should_InsertBreakBeforeBold_When_PrecededBySentenceEnd() {
        Book book = new Book();
        book.setId("punct-before-bold");
        book.setTitle("Punct Before Bold");
        book.setDescription(
            "<b>Intro</b><br>Ends with confidence.<b>What you will learn</b><br>● Item one"
        );

        BookDto dto = BookDtoMapper.toDto(book);

        String html = dto.descriptionContent().html();
        // Period should be followed by <br> before the bold heading
        assertThat(html).doesNotContain("confidence.<b>");
        assertThat(html).contains("<b>What you will learn</b>");
        assertThat(html).contains("<li>Item one</li>");
    }

    @Test
    void should_NeverProduceTripleBreaks_When_MultipleRulesOverlap() {
        Book book = new Book();
        book.setId("no-triple-br");
        book.setTitle("No Triple BR");
        book.setDescription(
            "Paragraph one.<br><br><b>Heading</b><br><br>Paragraph two."
        );

        BookDto dto = BookDtoMapper.toDto(book);

        String html = dto.descriptionContent().html();
        // Should never have 3+ consecutive <br> tags
        assertThat(html).doesNotContain("<br><br><br>");
        assertThat(html).contains("<b>Heading</b>");
    }

    @Test
    void should_HandleGoogleBooksDescription_When_FullAdHocPattern() {
        Book book = new Book();
        book.setId("google-books-full");
        book.setTitle("Google Books Full");
        book.setDescription(
            "<b>Key Features</b>● One<br>● Two<b><br></b>"
            + "<b>Description</b><b>\"Title\"</b> is a great book.<br><br>"
            + "More text.<b>TOC</b>1. Chapter<br>2. Chapter"
        );

        BookDto dto = BookDtoMapper.toDto(book);

        String html = dto.descriptionContent().html();
        // Bullets converted to list
        assertThat(html).contains("<li>One</li>");
        assertThat(html).contains("<li>Two</li>");
        // Consecutive bold blocks separated
        assertThat(html).doesNotContain("</b><b>");
        // Digit after bold separated
        assertThat(html).doesNotContain("</b>1.");
        // No inline bullet chars remain
        assertThat(html).doesNotContain("●");
        // No triple breaks
        assertThat(html).doesNotContain("<br><br><br>");
    }
}
