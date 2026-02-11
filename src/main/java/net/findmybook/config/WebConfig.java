/**
 * Configuration for static resource handling and web MVC settings
 * 
 * @author William Callahan
 *
 * Features:
 * - Configures cached cover image serving from file system
 * - Sets appropriate browser caching headers
 * - Resolves dynamic paths based on application properties
 * - Canonicalizes trailing slashes on page routes
 */
package net.findmybook.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.UrlHandlerFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String[] CANONICAL_PAGE_ROUTE_PATTERNS = {
        "/search",
        "/explore",
        "/categories",
        "/book/**",
        "/sitemap",
        "/sitemap/**",
        "/404",
        "/error"
    };

    private static final int PAGE_ROUTE_CANONICALIZATION_FILTER_ORDER =
        SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1;

    private final String coverCacheDirName;

    public WebConfig(@Value("${app.cover-cache.dir:book-covers}") String coverCacheDirName) {
        this.coverCacheDirName = coverCacheDirName;
    }

    /**
     * Configures resource handlers for static content serving.
     *
     * <p>Only registers handlers that need non-default behavior:
     * <ul>
     *   <li>{@code /frontend/**} -- no-cache revalidation for stable-named SPA bundles</li>
     *   <li>{@code /<coverCacheDirName>/**} -- 30-day browser cache for file-system cover images</li>
     * </ul>
     *
     * <p>The default {@code /**} and {@code /webjars/**} handlers are left to
     * Spring Boot's {@code WebMvcAutoConfiguration}, which applies the
     * {@code spring.web.resources} YAML cache/versioning settings automatically.
     *
     * @param registry the Spring MVC resource handler registry
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Frontend entry assets use stable filenames (app.js/app.css) so they must revalidate on each request.
        // Without this, long-lived browser caches can serve stale bundles after deployments.
        if (!registry.hasMappingForPattern("/frontend/**")) {
            registry.addResourceHandler("/frontend/**")
                    .addResourceLocations("classpath:/static/frontend/")
                    .setCacheControl(CacheControl.noCache().mustRevalidate());
        }

        // Resolve the absolute path to the cache directory.
        // This assumes 'coverCacheDirName' is a relative path from the application's working directory.
        Path cachePath = Paths.get(coverCacheDirName).toAbsolutePath();
        String resourceLocation = "file:" + cachePath.toString() + "/";

        // Serve files from the dynamically created cache directory.
        // For example, if coverCacheDirName is "covers", this handles "/covers/**".
        registry.addResourceHandler("/" + coverCacheDirName + "/**")
                .addResourceLocations(resourceLocation)
                .setCachePeriod(3600 * 24 * 30); // Cache for 30 days in browser

        // Spring Boot 4 WebMvcAutoConfiguration registers /** and /webjars/** handlers
        // with the spring.web.resources YAML cache/versioning settings applied automatically.
        // No custom registration needed here -- doing so would shadow those settings.
    }

    /**
     * Canonicalizes trailing slashes for page routes only with permanent redirects.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code /search/ -> /search}</li>
     *   <li>{@code /book/the-hobbit/ -> /book/the-hobbit}</li>
     * </ul>
     *
     * <p>Query strings are preserved by {@link UrlHandlerFilter}.
     *
     * @return configured trailing-slash canonicalization filter
     */
    @Bean
    public UrlHandlerFilter pageRouteTrailingSlashCanonicalizationFilter() {
        return UrlHandlerFilter
            .trailingSlashHandler(CANONICAL_PAGE_ROUTE_PATTERNS)
            .redirect(HttpStatus.PERMANENT_REDIRECT)
            .build();
    }

    /**
     * Registers trailing-slash canonicalization after forwarded headers and before security.
     *
     * @param pageRouteTrailingSlashCanonicalizationFilter canonicalization filter bean
     * @return ordered filter registration
     */
    @Bean
    public FilterRegistrationBean<UrlHandlerFilter> pageRouteTrailingSlashCanonicalizationFilterRegistration(
        UrlHandlerFilter pageRouteTrailingSlashCanonicalizationFilter
    ) {
        FilterRegistrationBean<UrlHandlerFilter> registration =
            new FilterRegistrationBean<>(pageRouteTrailingSlashCanonicalizationFilter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        registration.setOrder(PAGE_ROUTE_CANONICALIZATION_FILTER_ORDER);
        return registration;
    }
}
