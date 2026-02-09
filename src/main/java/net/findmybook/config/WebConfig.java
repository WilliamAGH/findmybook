/**
 * Configuration for static resource handling and web MVC settings
 * 
 * @author William Callahan
 *
 * Features:
 * - Configures cached cover image serving from file system
 * - Sets appropriate browser caching headers
 * - Resolves dynamic paths based on application properties
 * - Preserves standard resource handler mappings
 * - Supports both custom and default resource locations
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
     * Configures resource handlers for static content serving
     * - Dynamically maps cover image cache directory
     * - Sets appropriate cache control headers
     * - Preserves default static resource mappings
     * 
     * @param registry The Spring MVC resource handler registry
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

        // It's good practice to also explicitly register the default static resource handlers
        // if you're adding custom ones, to ensure they continue to work as expected.
        // Spring Boot's auto-configuration usually handles these, but being explicit can prevent surprises.
        if (!registry.hasMappingForPattern("/webjars/**")) {
            registry.addResourceHandler("/webjars/**")
                    .addResourceLocations("classpath:/META-INF/resources/webjars/");
        }
        if (!registry.hasMappingForPattern("/**")) {
            registry.addResourceHandler("/**")
                    .addResourceLocations("classpath:/META-INF/resources/",
                                        "classpath:/resources/",
                                        "classpath:/static/",
                                        "classpath:/public/");
        }
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
