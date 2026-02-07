package net.findmybook.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.findmybook.controller.BookController;
import net.findmybook.dto.BookListItem;
import net.findmybook.repository.BookQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(SearchPaginationService.class)
class BookControllerPaginationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BookDataOrchestrator bookDataOrchestrator;

    @MockitoBean
    private BookSearchService bookSearchService;

    @MockitoBean
    private BookQueryRepository bookQueryRepository;

    @MockitoBean
    private BookIdentifierResolver bookIdentifierResolver;

    @MockitoBean
    private CacheManager cacheManager;

    private final List<UUID> bookIds = new ArrayList<>();
    private final List<BookSearchService.SearchResult> searchResults = new ArrayList<>();
    private final List<BookListItem> listItems = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bookIds.clear();
        searchResults.clear();
        listItems.clear();
        
        // Create 29 books (same as original test had 14+15)
        IntStream.range(0, 29).forEach(i -> {
            UUID id = UUID.randomUUID();
            bookIds.add(id);
            searchResults.add(new BookSearchService.SearchResult(id, 0.9 - (i * 0.01), "TSVECTOR"));
            listItems.add(buildListItem(id, "Book %02d".formatted(i)));
        });

        when(bookSearchService.searchBooks(anyString(), anyInt()))
            .thenReturn(searchResults);
        when(bookQueryRepository.fetchBookListItems(any()))
            .thenReturn(listItems);
    }

    @Test
    @DisplayName("Page 1 honors Postgres-first ordering and exposes prefetch metadata")
    void firstPageMaintainsOrderingAndPrefetch() throws Exception {
        MvcResult result = performAsync(get("/api/books/search")
                .param("query", "multi")
                .param("maxResults", "12"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.results", org.hamcrest.Matchers.hasSize(12)))
            .andExpect(jsonPath("$.results[0].id", org.hamcrest.Matchers.equalTo(bookIds.get(0).toString())))
            .andExpect(jsonPath("$.results[11].id", org.hamcrest.Matchers.equalTo(bookIds.get(11).toString())))
            .andExpect(jsonPath("$.hasMore", org.hamcrest.Matchers.equalTo(true)))
            .andExpect(jsonPath("$.nextStartIndex", org.hamcrest.Matchers.equalTo(12)))
            .andExpect(jsonPath("$.prefetchedCount", org.hamcrest.Matchers.equalTo(17)))
            .andReturn();

        List<String> ids = extractIds(result);
        assertThat(new HashSet<>(ids)).hasSize(ids.size());
    }

    @Test
    @DisplayName("Page 2 advances cursor without duplicating page 1 results")
    void secondPageAdvancesCursorWithoutDupes() throws Exception {
        MvcResult firstPage = performAsync(get("/api/books/search")
                .param("query", "multi")
                .param("maxResults", "12"))
            .andExpect(status().isOk())
            .andReturn();

        MvcResult secondPage = performAsync(get("/api/books/search")
                .param("query", "multi")
                .param("startIndex", "12")
                .param("maxResults", "12"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.results", org.hamcrest.Matchers.hasSize(12)))
            .andExpect(jsonPath("$.results[0].id", org.hamcrest.Matchers.equalTo(bookIds.get(12).toString())))
            .andExpect(jsonPath("$.results[1].id", org.hamcrest.Matchers.equalTo(bookIds.get(13).toString())))
            .andExpect(jsonPath("$.results[2].id", org.hamcrest.Matchers.equalTo(bookIds.get(14).toString())))
            .andExpect(jsonPath("$.hasMore", org.hamcrest.Matchers.equalTo(true)))
            .andExpect(jsonPath("$.nextStartIndex", org.hamcrest.Matchers.equalTo(24)))
            .andExpect(jsonPath("$.prefetchedCount", org.hamcrest.Matchers.equalTo(5)))
            .andReturn();

        List<String> firstIds = extractIds(firstPage);
        List<String> secondIds = extractIds(secondPage);

        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
        assertThat(new HashSet<>(secondIds)).hasSize(secondIds.size());
    }

    private List<String> extractIds(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        root.path("results").forEach(node -> ids.add(node.path("id").asString()));
        return ids;
    }

    private ResultActions performAsync(MockHttpServletRequestBuilder builder) throws Exception {
        builder.accept(MediaType.APPLICATION_JSON);
        ResultActions initial = mockMvc.perform(builder);
        MvcResult mvcResult = initial.andReturn();
        if (mvcResult.getRequest().isAsyncStarted()) {
            return mockMvc.perform(asyncDispatch(mvcResult));
        }
        return initial;
    }

    private BookListItem buildListItem(UUID id, String title) {
        Map<String, Object> tags = new HashMap<>();
        tags.put("nytBestseller", Map.<String, Object>of("rank", 1));
        return new BookListItem(
            id.toString(),
            "slug-" + id,
            title,
            title + " description",
            List.of("Author"),
            List.of("Category"),
            "https://example.test/" + id + ".jpg",
            "s3://covers/" + id + ".jpg",
            "https://fallback.example.test/" + id + ".jpg",
            600,
            900,
            true,
            4.5,
            100,
            tags
        );
    }
}
