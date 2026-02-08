package net.findmybook.controller;

import net.findmybook.dto.BookCard;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.service.EnvironmentService;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.EnumParsingUtils;
import net.findmybook.util.SearchQueryUtils;
import net.findmybook.util.cover.CoverPrioritizer;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;

@Controller
@Slf4j
public class HomeController {

    private final EnvironmentService environmentService;
    private final SearchPaginationService searchPaginationService;
    private final HomePageSectionsService homePageSectionsService;
    private final BookSeoMetadataService bookSeoMetadataService;
    private final boolean isYearFilteringEnabled;
    private final boolean spaFrontendEnabled;

    private static final int MAX_RECENT_BOOKS = 8;
    private static final int MAX_BESTSELLERS = 8;

    private static final List<String> EXPLORE_QUERIES = List.of(
        "Classic literature", "Modern thrillers", "Space opera adventures", "Historical fiction bestsellers",
        "Award-winning science fiction", "Inspiring biographies", "Mind-bending philosophy", "Beginner's cookbooks",
        "Epic fantasy sagas", "Cyberpunk futures", "Cozy mysteries", "Environmental science",
        "Artificial intelligence ethics", "World mythology", "Travel memoirs"
    );
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    /**
     * Constructs the HomeController with required services.
     */
    public HomeController(EnvironmentService environmentService,
                          HomePageSectionsService homePageSectionsService,
                          BookSeoMetadataService bookSeoMetadataService,
                          @Value("${app.feature.year-filtering.enabled:false}") boolean isYearFilteringEnabled,
                          @Value("${app.frontend.spa.enabled:true}") boolean spaFrontendEnabled,
                          SearchPaginationService searchPaginationService) {
        this.environmentService = environmentService;
        this.homePageSectionsService = homePageSectionsService;
        this.bookSeoMetadataService = bookSeoMetadataService;
        this.isYearFilteringEnabled = isYearFilteringEnabled;
        this.spaFrontendEnabled = spaFrontendEnabled;
        this.searchPaginationService = searchPaginationService;
    }

    private void applyBaseAttributes(Model model, String activeTab) {
        model.addAttribute("isDevelopmentMode", environmentService.isDevelopmentMode());
        model.addAttribute("currentEnv", environmentService.getCurrentEnvironmentMode());
        model.addAttribute("activeTab", activeTab);
    }

    /**
     * Determines the active navigation tab based on the source parameter
     * @param source The source parameter from navigation (explore, categories, or null)
     * @return The active tab name for navigation highlighting
     */
    private String determineActiveTab(String source) {
        if ("explore".equals(source)) {
            return "explore";
        }
        if ("categories".equals(source)) {
            return "categories";
        }
        return "search";
    }

    /** Renders the homepage with bestsellers and recently viewed sections. */
    @GetMapping("/")
    public Mono<String> home(Model model) {
        applyBaseAttributes(model, "home");
        bookSeoMetadataService.apply(model, bookSeoMetadataService.homeMetadata());

        if (spaFrontendEnabled) {
            return Mono.just("spa/index");
        }

        // Set default empty collections for immediate rendering
        model.addAttribute("currentBestsellers", List.<BookCard>of());
        model.addAttribute("recentBooks", List.<BookCard>of());

        Mono<List<BookCard>> bestsellers = homePageSectionsService.loadCurrentBestsellers(MAX_BESTSELLERS)
            .map(list -> retainRenderableHomepageCards(list, MAX_BESTSELLERS))
            .timeout(Duration.ofMillis(3000))
            .onErrorMap(e -> {
                if (e instanceof java.util.concurrent.TimeoutException) {
                    log.warn("Bestsellers timed out after 3000ms");
                } else {
                    log.warn("Bestsellers failed: {}", e.getMessage());
                }
                return new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Homepage bestsellers load failed",
                    e
                );
            })
            .doOnNext(list -> {
                model.addAttribute("currentBestsellers", list);
                if (!list.isEmpty()) {
                    log.debug("Homepage: Loaded {} bestsellers successfully", list.size());
                }
            });

        Mono<List<BookCard>> recentBooks = homePageSectionsService.loadRecentBooks(MAX_RECENT_BOOKS)
            .map(list -> retainRenderableHomepageCards(list, MAX_RECENT_BOOKS))
            .timeout(Duration.ofMillis(3000))
            .onErrorMap(e -> {
                if (e instanceof java.util.concurrent.TimeoutException) {
                    log.warn("Recent books timed out after 3000ms");
                } else {
                    log.warn("Recent books failed: {}", e.getMessage());
                }
                return new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Homepage recent books load failed",
                    e
                );
            })
            .doOnNext(list -> {
                model.addAttribute("recentBooks", list);
                if (!list.isEmpty()) {
                    log.debug("Homepage: Loaded {} recent books successfully", list.size());
                }
            });

        return Mono.zipDelayError(bestsellers, recentBooks)
            .then(Mono.just("index"))
            .onErrorMap(e -> {
                log.error("Critical error loading homepage sections: {}", e.getMessage(), e);
                return new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Critical error loading homepage sections",
                    e
                );
            });
    }

    /** Renders the search page and server-side initial results window when query is present. */
    @GetMapping("/search")
    public Mono<String> search(@RequestParam(required = false) String query,
                               @RequestParam(required = false) Integer year,
                               @RequestParam(required = false, defaultValue = "0") int page,
                               @RequestParam(required = false, defaultValue = "newest") String sort,
                               @RequestParam(required = false) String source,
                               @RequestParam(required = false, defaultValue = "ANY") String coverSource,
                               @RequestParam(required = false, defaultValue = "ANY") String resolution,
                               Model model) {
        // Handle year extraction from query if enabled
        if (isYearFilteringEnabled && year == null && StringUtils.hasText(query)) {
            Matcher matcher = YEAR_PATTERN.matcher(query);
            if (matcher.find()) {
                int extractedYear = Integer.parseInt(matcher.group(1));
                log.info("Detected year {} in query text. Redirecting to use year parameter.", extractedYear);

                String processedQuery = (query.substring(0, matcher.start()) + query.substring(matcher.end()))
                    .trim()
                    .replaceAll("\\s+", " ");

                UriComponentsBuilder redirectBuilder = UriComponentsBuilder.fromPath("/search")
                    .queryParamIfPresent("query", StringUtils.hasText(processedQuery) ? Optional.of(processedQuery) : Optional.empty())
                    .queryParam("year", extractedYear);

                if (StringUtils.hasText(sort)) {
                    redirectBuilder.queryParam("sort", sort);
                }
                if (StringUtils.hasText(source)) {
                    redirectBuilder.queryParam("source", source);
                }
                if (StringUtils.hasText(coverSource)) {
                    redirectBuilder.queryParam("coverSource", coverSource);
                }
                if (StringUtils.hasText(resolution)) {
                    redirectBuilder.queryParam("resolution", resolution);
                }

                String redirectPath = redirectBuilder.build().encode(StandardCharsets.UTF_8).toUriString();
                return Mono.just("redirect:" + redirectPath);
            }
        }

        Integer effectiveYear = isYearFilteringEnabled ? year : null;
        CoverImageSource coverSourcePreference = EnumParsingUtils.parseOrDefault(
            coverSource,
            CoverImageSource.class,
            CoverImageSource.ANY,
            raw -> log.debug("Invalid coverSource '{}' on /search view request, defaulting to ANY", raw)
        );
        ImageResolutionPreference resolutionPreference = EnumParsingUtils.parseOrDefault(
            resolution,
            ImageResolutionPreference.class,
            ImageResolutionPreference.ANY,
            raw -> log.debug("Invalid resolution '{}' on /search view request, defaulting to ANY", raw)
        );

        String activeTab = determineActiveTab(source);
        applyBaseAttributes(model, activeTab);
        model.addAttribute("query", query);
        model.addAttribute("year", effectiveYear);
        model.addAttribute("currentPage", page);
        model.addAttribute("currentSort", sort);
        model.addAttribute("source", source);
        model.addAttribute("coverSource", coverSourcePreference.name());
        model.addAttribute("resolution", resolutionPreference.name());
        model.addAttribute("isYearFilteringEnabled", isYearFilteringEnabled);

        bookSeoMetadataService.apply(model, bookSeoMetadataService.searchMetadata());

        if (spaFrontendEnabled) {
            return Mono.just("spa/index");
        }

        // Server-render initial results if query provided
        if (StringUtils.hasText(query)) {
            final int pageSize = ApplicationConstants.Paging.DEFAULT_SEARCH_LIMIT;
            final int safePage = Math.max(page, 0);
            final int startIndex = Math.multiplyExact(safePage, pageSize);
            String normalizedQuery = SearchQueryUtils.normalize(query);

            SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
                normalizedQuery,
                startIndex,
                pageSize,
                sort,
                coverSourcePreference,
                resolutionPreference,
                effectiveYear
            );

            return searchPaginationService.search(request)
                .map(result -> {
                    List<Book> windowed = result.pageItems();
                    model.addAttribute("initialResults", windowed);
                    model.addAttribute("hasInitialResults", !windowed.isEmpty());
                    model.addAttribute("totalResults", result.totalUnique());
                    model.addAttribute("initialResultsError", null);
                    log.info("Server-rendered {} search results for query '{}'", windowed.size(), query);
                    return "search";
                })
                .onErrorMap(e -> {
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        log.warn("Timeout server-rendering search results for '{}': {}", query, e.getMessage());
                    } else {
                        log.warn("Error server-rendering search results for '{}': {}", query, e.getMessage(), e);
                    }
                    return new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Server-rendered search failed for query: " + query,
                        e
                    );
                });
        }

        // No query - show search page with no results
        model.addAttribute("initialResults", List.of());
        model.addAttribute("hasInitialResults", false);
        return Mono.just("search");
    }

    /** Handles `/explore` route for SPA and server-rendered modes. */
    @GetMapping("/explore")
    public Object explore(Model model) {
        if (spaFrontendEnabled) {
            applyBaseAttributes(model, "explore");
            bookSeoMetadataService.apply(model, bookSeoMetadataService.searchMetadata());
            return "spa/index";
        }

        String selectedQuery = EXPLORE_QUERIES.get(ThreadLocalRandom.current().nextInt(EXPLORE_QUERIES.size()));
        log.info("Explore page requested, redirecting to search with query: '{}'", selectedQuery);
        String encodedQuery = URLEncoder.encode(selectedQuery, StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(URI.create("/search?query=" + encodedQuery + "&source=explore"))
            .build();
    }

    /** Handles `/categories` route for SPA and server-rendered modes. */
    @GetMapping("/categories")
    public Object categories(Model model) {
        if (spaFrontendEnabled) {
            applyBaseAttributes(model, "categories");
            bookSeoMetadataService.apply(model, bookSeoMetadataService.searchMetadata());
            return "spa/index";
        }

        String selectedQuery = EXPLORE_QUERIES.get(ThreadLocalRandom.current().nextInt(EXPLORE_QUERIES.size()));
        log.info("Categories page requested, redirecting to search with query: '{}'", selectedQuery);
        String encodedQuery = URLEncoder.encode(selectedQuery, StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(URI.create("/search?query=" + encodedQuery + "&source=categories"))
            .build();
    }

    private List<BookCard> retainRenderableHomepageCards(List<BookCard> cards, int maxItems) {
        if (cards == null || cards.isEmpty() || maxItems <= 0) {
            return List.of();
        }

        List<BookCard> filtered = new ArrayList<>(Math.min(cards.size(), maxItems));
        for (BookCard card : cards) {
            if (card == null || CoverPrioritizer.score(card) <= 0) {
                continue;
            }
            filtered.add(card);
            if (filtered.size() >= maxItems) {
                break;
            }
        }
        return List.copyOf(filtered);
    }
}
