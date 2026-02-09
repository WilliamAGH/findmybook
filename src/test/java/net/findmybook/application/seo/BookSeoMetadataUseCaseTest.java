package net.findmybook.application.seo;

import java.util.List;
import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.model.Book;
import net.findmybook.support.seo.BookGraphRenderRequest;
import net.findmybook.support.seo.BookOpenGraphImageResolver;
import net.findmybook.support.seo.BookOpenGraphPropertyFactory;
import net.findmybook.support.seo.BookStructuredDataRenderer;
import net.findmybook.support.seo.CanonicalUrlResolver;
import net.findmybook.support.seo.RouteStructuredDataRenderer;
import net.findmybook.support.seo.SeoMarkupFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookSeoMetadataUseCaseTest {

    @Mock
    private BookStructuredDataRenderer bookStructuredDataRenderer;
    @Mock
    private BookOpenGraphPropertyFactory bookOpenGraphPropertyFactory;
    @Mock
    private BookOpenGraphImageResolver bookOpenGraphImageResolver;
    @Mock
    private CanonicalUrlResolver canonicalUrlResolver;
    @Mock
    private SeoMarkupFormatter seoMarkupFormatter;
    @Mock
    private RouteStructuredDataRenderer routeStructuredDataRenderer;

    private BookSeoMetadataUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new BookSeoMetadataUseCase(
            bookStructuredDataRenderer,
            bookOpenGraphPropertyFactory,
            bookOpenGraphImageResolver,
            canonicalUrlResolver,
            seoMarkupFormatter,
            routeStructuredDataRenderer
        );
    }

    @Test
    void should_UseFallbackConstants_When_BookIdentifierIsUnresolved() {
        when(canonicalUrlResolver.normalizePublicUrl(eq("/book/unknown-slug")))
            .thenReturn("https://findmybook.net/book/unknown-slug");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString()))
            .thenReturn("Book Details | findmybook");
        when(routeStructuredDataRenderer.renderRouteGraph(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq("WebPage")
        )).thenReturn("{\"fallback\":true}");

        SeoMetadata metadata = useCase.bookFallbackMetadata("unknown-slug");

        assertEquals("Book Details", metadata.title());
        assertTrue(metadata.description().contains("findmybook"));
        assertTrue(metadata.keywords().contains("findmybook book details"));
        assertEquals("https://findmybook.net/book/unknown-slug", metadata.canonicalUrl());
        assertEquals(SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW, metadata.robots());
        assertEquals(SeoPresentationDefaults.OPEN_GRAPH_TYPE_WEBSITE, metadata.openGraphType());
    }

    @Test
    void should_BuildBookMetadata_When_BookIsResolved() {
        Book book = new Book();
        book.setId("abc-123");
        book.setSlug("the-great-gatsby");
        book.setTitle("The Great Gatsby");
        book.setDescription("A novel by F. Scott Fitzgerald");
        book.setIsbn13("9780743273565");
        book.setAuthors(List.of("F. Scott Fitzgerald"));

        when(canonicalUrlResolver.normalizePublicUrl(eq("/book/the-great-gatsby")))
            .thenReturn("https://findmybook.net/book/the-great-gatsby");
        when(canonicalUrlResolver.normalizePublicUrl(eq("/images/og-logo.png")))
            .thenReturn("https://findmybook.net/images/og-logo.png");
        when(bookOpenGraphImageResolver.resolveBookImage(eq(book), anyString()))
            .thenReturn("/images/og-logo.png");
        when(seoMarkupFormatter.pageTitle(eq("The Great Gatsby"), anyString(), anyString()))
            .thenReturn("The Great Gatsby | findmybook");
        when(bookStructuredDataRenderer.renderBookGraph(any(BookGraphRenderRequest.class)))
            .thenReturn("{\"@type\":\"Book\"}");
        when(bookOpenGraphPropertyFactory.fromBook(eq(book)))
            .thenReturn(List.of(
                new net.findmybook.support.seo.OpenGraphProperty("book:isbn", "9780743273565")
            ));

        SeoMetadata metadata = useCase.bookMetadata(book, 170);

        assertEquals("The Great Gatsby", metadata.title());
        assertEquals("https://findmybook.net/book/the-great-gatsby", metadata.canonicalUrl());
        assertEquals(SeoPresentationDefaults.OPEN_GRAPH_TYPE_BOOK, metadata.openGraphType());
        assertEquals(SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW, metadata.robots());
        assertEquals("{\"@type\":\"Book\"}", metadata.structuredDataJson());
        assertEquals(1, metadata.openGraphProperties().size());
        assertEquals("book:isbn", metadata.openGraphProperties().getFirst().property());
    }

    @Test
    void should_UseIdAsCanonicalIdentifier_When_SlugIsBlank() {
        Book book = new Book();
        book.setId("abc-123");
        book.setSlug(null);
        book.setTitle("Untitled");

        when(canonicalUrlResolver.normalizePublicUrl(anyString()))
            .thenReturn("https://findmybook.net/images/og-logo.png");
        when(canonicalUrlResolver.normalizePublicUrl(eq("/book/abc-123")))
            .thenReturn("https://findmybook.net/book/abc-123");
        when(bookOpenGraphImageResolver.resolveBookImage(eq(book), anyString()))
            .thenReturn("/images/og-logo.png");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString()))
            .thenReturn("Untitled | findmybook");
        when(bookStructuredDataRenderer.renderBookGraph(any(BookGraphRenderRequest.class)))
            .thenReturn("{}");
        when(bookOpenGraphPropertyFactory.fromBook(eq(book)))
            .thenReturn(List.of());

        SeoMetadata metadata = useCase.bookMetadata(book, 170);

        verify(canonicalUrlResolver).normalizePublicUrl("/book/abc-123");
        assertEquals("https://findmybook.net/book/abc-123", metadata.canonicalUrl());
    }

    @Test
    void should_PassBookGraphRenderRequest_When_BookMetadataIsBuilt() {
        Book book = new Book();
        book.setId("abc-123");
        book.setSlug("test-book");
        book.setTitle("Test Book");

        when(canonicalUrlResolver.normalizePublicUrl(anyString())).thenReturn("https://findmybook.net/book/test-book");
        when(bookOpenGraphImageResolver.resolveBookImage(any(), anyString())).thenReturn("/og.png");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString())).thenReturn("Test Book | findmybook");
        when(bookStructuredDataRenderer.renderBookGraph(any(BookGraphRenderRequest.class))).thenReturn("{}");
        when(bookOpenGraphPropertyFactory.fromBook(any())).thenReturn(List.of());

        useCase.bookMetadata(book, 170);

        ArgumentCaptor<BookGraphRenderRequest> captor = ArgumentCaptor.forClass(BookGraphRenderRequest.class);
        verify(bookStructuredDataRenderer).renderBookGraph(captor.capture());

        BookGraphRenderRequest request = captor.getValue();
        assertEquals(book, request.book());
        assertEquals("Test Book | findmybook", request.webPageTitle());
        assertEquals("Test Book", request.bookTitle());
    }

    @Test
    void should_ThrowIllegalArgument_When_BookIsNull() {
        assertThrows(IllegalArgumentException.class, () -> useCase.bookMetadata(null, 170));
    }
}
