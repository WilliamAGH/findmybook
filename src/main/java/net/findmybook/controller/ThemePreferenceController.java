package net.findmybook.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * Controller for managing user theme preferences (light/dark mode)
 *
 * @author William Callahan
 *
 * Features:
 * - Persists theme preferences via cookies
 * - Supports explicit light/dark mode selection
 * - Allows fallback to system theme preference
 * - Exposes REST API for theme operations
 * - Implements secure cookie management
 */
@Controller
@RequestMapping("/api/theme")
public class ThemePreferenceController {

    private static final String THEME_COOKIE_NAME = "preferred_theme";
    private static final int COOKIE_MAX_AGE = 365 * 24 * 60 * 60; // 1 year in seconds

    /**
     * Get the current theme preference
     *
     * @param request HTTP request
     * @return Theme preference response
     */
    @GetMapping
    @ResponseBody
    public ResponseEntity<ThemePreferenceResponse> getThemePreference(HttpServletRequest request) {
        String themeValue = getThemeCookieValue(request);
        return ResponseEntity.ok(ThemePreferenceResponse.from(themeValue));
    }

    /**
     * Update the theme preference
     *
     * @param themeData Theme data map
     * @param response HTTP response
     * @return Updated theme preference
     */
    @PostMapping
    @ResponseBody
    public ResponseEntity<ThemePreferenceResponse> updateThemePreference(
            @RequestBody Map<String, String> themeData,
            HttpServletResponse response) {

        String themeValue = themeData.get("theme");
        boolean useSystem = Boolean.parseBoolean(themeData.getOrDefault("useSystem", "false"));

        if (useSystem) {
            // Delete the cookie to use system preference
            Cookie cookie = new Cookie(THEME_COOKIE_NAME, null);
            cookie.setMaxAge(0);
            cookie.setPath("/");
            response.addCookie(cookie);
            return ResponseEntity.ok(ThemePreferenceResponse.system());
        } else if (themeValue != null && (themeValue.equals("light") || themeValue.equals("dark"))) {
            // Set a cookie with the theme preference
            Cookie cookie = new Cookie(THEME_COOKIE_NAME, themeValue);
            cookie.setMaxAge(COOKIE_MAX_AGE);
            cookie.setPath("/");
            response.addCookie(cookie);
            return ResponseEntity.ok(ThemePreferenceResponse.user(themeValue));
        } else {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Theme must be 'light' or 'dark' when useSystem is false."
            );
        }
    }

    /**
     * Helper method to extract theme preference from request cookies
     * - Searches for theme cookie in the request
     * - Returns null if cookie is not found
     * - Safely handles empty cookie arrays
     * - Uses Java 8 Stream API for clean cookie processing
     *
     * @param request HttpServletRequest containing cookies
     * @return Theme value from cookie or null if not found
     */
    private String getThemeCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        Optional<Cookie> themeCookie = Arrays.stream(cookies)
                .filter(c -> THEME_COOKIE_NAME.equals(c.getName()))
                .findFirst();

        return themeCookie.map(Cookie::getValue).orElse(null);
    }

    public record ThemePreferenceResponse(String theme, String source) {
        static ThemePreferenceResponse from(String theme) {
            return theme == null ? system() : user(theme);
        }

        static ThemePreferenceResponse system() {
            return new ThemePreferenceResponse(null, "system");
        }

        static ThemePreferenceResponse user(String theme) {
            return new ThemePreferenceResponse(theme, "user");
        }
    }
}
