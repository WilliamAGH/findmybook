package net.findmybook.controller;

import net.findmybook.service.BookSeoMetadataService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

@Controller
@Slf4j
public class HomeController {

    private final BookSeoMetadataService bookSeoMetadataService;
    private final boolean isYearFilteringEnabled;
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    /**
     * Constructs the SPA-first home controller.
     */
    public HomeController(BookSeoMetadataService bookSeoMetadataService,
                          @Value("${app.feature.year-filtering.enabled:false}") boolean isYearFilteringEnabled) {
        this.bookSeoMetadataService = bookSeoMetadataService;
        this.isYearFilteringEnabled = isYearFilteringEnabled;
    }

    /** Renders the homepage SPA shell with server-side SEO metadata. */
    @GetMapping("/")
    public Mono<ResponseEntity<String>> home() {
        return Mono.just(spaResponse(bookSeoMetadataService.homeMetadata(), "/", HttpStatus.OK));
    }

    /** Renders the search SPA shell and preserves year parameter canonicalization. */
    @GetMapping("/search")
    public Mono<ResponseEntity<String>> search(@RequestParam(required = false) String query,
                                               @RequestParam(required = false) Integer year,
                                               @RequestParam(required = false, defaultValue = "0") int page,
                                               @RequestParam(required = false, defaultValue = "newest") String orderBy,
                                               @RequestParam(required = false) String source,
                                               @RequestParam(required = false, defaultValue = "ANY") String coverSource,
                                               @RequestParam(required = false, defaultValue = "ANY") String resolution) {
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

                if (StringUtils.hasText(orderBy)) {
                    redirectBuilder.queryParam("orderBy", orderBy);
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
                return Mono.just(ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(java.net.URI.create(redirectPath))
                    .build());
            }
        }

        return Mono.just(spaResponse(bookSeoMetadataService.searchMetadata(), "/search", HttpStatus.OK));
    }

    /** Renders the explore SPA shell with server-side metadata. */
    @GetMapping("/explore")
    public ResponseEntity<String> explore() {
        return spaResponse(bookSeoMetadataService.exploreMetadata(), "/explore", HttpStatus.OK);
    }

    /** Renders the categories SPA shell with server-side metadata. */
    @GetMapping("/categories")
    public ResponseEntity<String> categories() {
        return spaResponse(bookSeoMetadataService.categoriesMetadata(), "/categories", HttpStatus.OK);
    }

    private ResponseEntity<String> spaResponse(BookSeoMetadataService.SeoMetadata metadata,
                                               String requestPath,
                                               HttpStatus status) {
        String html = bookSeoMetadataService.renderSpaShell(metadata, requestPath, status.value());
        return ResponseEntity.status(status)
            .contentType(MediaType.TEXT_HTML)
            .body(html);
    }
}
