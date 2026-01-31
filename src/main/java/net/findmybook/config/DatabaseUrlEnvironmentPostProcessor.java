package net.findmybook.config;

import java.util.Locale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.HashMap;
import java.util.Map;

/**
 * Normalizes SPRING_DATASOURCE_URL values provided as Postgres URI (postgres://...)
 * into a JDBC URL (jdbc:postgresql://...).
 *
 * Also sets spring.datasource.username and spring.datasource.password from the URI
 * user-info if not already provided by higher-precedence sources.
 */
public final class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String DS_URL = "spring.datasource.url";
    private static final String ENV_DS_URL = "SPRING_DATASOURCE_URL";
    private static final String DS_JDBC_URL = "spring.datasource.jdbc-url";
    private static final String HIKARI_JDBC_URL = "spring.datasource.hikari.jdbc-url";
    private static final String DS_USERNAME = "spring.datasource.username";
    private static final String DS_PASSWORD = "spring.datasource.password";
    private static final String DS_DRIVER = "spring.datasource.driver-class-name";
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_DATABASE = "postgres";
    private static final int DEFAULT_PORT = 5432;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /**
     * Checks if a string has meaningful content (not null, not empty, not blank).
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Validates that a port number is within the valid TCP/UDP port range (1-65535).
     */
    private static boolean isValidPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }

    /**
     * Value object for parsed host and port.
     */
    private record HostPort(String host, int port) {}

    /**
     * Defaults empty host to localhost (PostgreSQL convention).
     */
    private static String defaultHost(String host) {
        return hasText(host) ? host : DEFAULT_HOST;
    }

    /**
     * Parses host:port string into a HostPort value object.
     * Falls back to defaultPort if port is missing, invalid, or out of range.
     * Falls back to localhost if host is empty (PostgreSQL convention for local connections).
     * Handles bracketed IPv6 addresses (e.g., [::1]:5432).
     *
     * <p><strong>Design Note - System.err Usage:</strong> This method intentionally uses
     * {@code System.err} instead of SLF4J logging because it executes during the
     * {@link EnvironmentPostProcessor} phase of Spring Boot startup. At this point:</p>
     * <ul>
     *   <li>The logging subsystem is not yet initialized</li>
     *   <li>LoggerFactory.getLogger() would return a NOP logger or cause initialization errors</li>
     *   <li>Configuration errors at this stage are critical and must be visible to operators</li>
     * </ul>
     * <p>This is a standard Spring Boot pattern for EnvironmentPostProcessor implementations.</p>
     *
     * @param hostPortString the host:port string to parse
     * @param defaultPort fallback port if parsing fails or port is invalid
     * @return parsed HostPort with validated port and non-empty host
     */
    private static HostPort parseHostPort(String hostPortString, int defaultPort) {
        // Handle bracketed IPv6 addresses (e.g., [::1]:5432)
        if (hostPortString.startsWith("[")) {
            int endBracket = hostPortString.indexOf(']');
            if (endBracket > 0) {
                String host = defaultHost(hostPortString.substring(1, endBracket));
                int port = defaultPort;
                if (endBracket + 1 < hostPortString.length()
                    && hostPortString.charAt(endBracket + 1) == ':') {
                    String portString = hostPortString.substring(endBracket + 2);
                    try {
                        int parsedPort = Integer.parseInt(portString);
                        if (isValidPort(parsedPort)) {
                            return new HostPort(host, parsedPort);
                        }
                        logBootstrapWarning("Invalid port " + parsedPort
                            + " (must be " + MIN_PORT + "-" + MAX_PORT + "), using default " + defaultPort);
                    } catch (NumberFormatException e) {
                        logBootstrapWarning("Non-numeric port '" + portString + "', using default " + defaultPort);
                    }
                }
                return new HostPort(host, port);
            }
        }

        if (!hostPortString.contains(":")) {
            return new HostPort(defaultHost(hostPortString), defaultPort);
        }

        String[] parts = hostPortString.split(":", 2);
        String host = defaultHost(parts[0]);
        String portString = parts[1];

        try {
            int parsedPort = Integer.parseInt(portString);
            if (isValidPort(parsedPort)) {
                return new HostPort(host, parsedPort);
            }
            logBootstrapWarning("Invalid port " + parsedPort
                + " (must be " + MIN_PORT + "-" + MAX_PORT + "), using default " + defaultPort);
        } catch (NumberFormatException e) {
            logBootstrapWarning("Non-numeric port '" + portString + "', using default " + defaultPort);
        }
        return new HostPort(host, defaultPort);
    }

    /**
     * Logs a warning message during bootstrap before SLF4J is available.
     * Uses System.err as this runs before logging infrastructure is initialized.
     */
    private static void logBootstrapWarning(String message) {
        System.err.println("[DatabaseUrlEnvironmentPostProcessor] " + message);
    }

    /**
     * Parsed JDBC output for a Postgres URL. username/password may be null if not provided.
     */
    public static final class JdbcParseResult {
        public final String jdbcUrl;
        public final String username;
        public final String password;
        public JdbcParseResult(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }
    }

    /**
     * Convert a postgres:// or postgresql:// URL to a JDBC URL and extract optional credentials.
     * Returns empty when the input is blank or not a Postgres URL.
     */
    public static java.util.Optional<JdbcParseResult> normalizePostgresUrl(String url) {
        if (!hasText(url)) return java.util.Optional.empty();
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("postgres://") || lower.startsWith("postgresql://"))) {
            return java.util.Optional.empty();
        }

        // Manual parsing to handle postgres:// format properly
        // Format: postgres://username:password@host:port/database?params
        String withoutScheme = url.substring(url.indexOf("://") + 3);

        String username = null;
        String password = null;
        String hostPart;

        // Check if credentials are present
        if (withoutScheme.contains("@")) {
            String[] parts = withoutScheme.split("@", 2);
            String userInfo = parts[0];
            hostPart = parts[1];

            // Extract username and password
            if (userInfo.contains(":")) {
                int colonIndex = userInfo.indexOf(":");
                username = userInfo.substring(0, colonIndex);
                password = userInfo.substring(colonIndex + 1);
            } else {
                username = userInfo;
            }
        } else {
            hostPart = withoutScheme;
        }

        // Parse host, port, database, and query params
        String host;
        int port = DEFAULT_PORT;
        String database = DEFAULT_DATABASE;
        String query = null;

        // Split by ? to separate query params
        if (hostPart.contains("?")) {
            String[] queryParts = hostPart.split("\\?", 2);
            hostPart = queryParts[0];
            query = queryParts[1];
        }

        // Split by / to separate database
        if (hostPart.contains("/")) {
            String[] dbParts = hostPart.split("/", 2);
            String hostPortPart = dbParts[0];
            String dbName = dbParts[1];
            // Handle trailing slash case (empty database name) - fall back to default
            if (hasText(dbName)) {
                database = dbName;
            }
            // else: keep DEFAULT_DATABASE

            // Extract host and port
            HostPort parsed = parseHostPort(hostPortPart, port);
            host = parsed.host();
            port = parsed.port();
        } else {
            // No database specified in URL
            HostPort parsed = parseHostPort(hostPart, port);
            host = parsed.host();
            port = parsed.port();
        }

        // Build JDBC URL
        StringBuilder jdbc = new StringBuilder()
                .append("jdbc:postgresql://")
                .append(host)
                .append(":")
                .append(port)
                .append("/")
                .append(database);
        if (hasText(query)) {
            jdbc.append("?").append(query);
        }
        return java.util.Optional.of(new JdbcParseResult(jdbc.toString(), username, password));
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty(DS_URL);
        if (!hasText(url)) {
            // Fallback to raw env var if application.yml hasn't mapped it yet
            url = environment.getProperty(ENV_DS_URL);
        }
        java.util.Optional<JdbcParseResult> parsed = normalizePostgresUrl(url);
        if (parsed.isEmpty()) {
            return;
        }

        try {
            JdbcParseResult result = parsed.get();
            Map<String, Object> overrides = new HashMap<>();
            overrides.put(DS_URL, result.jdbcUrl);
            // Also set commonly used aliases so Hikari picks up the normalized URL reliably
            overrides.put(DS_JDBC_URL, result.jdbcUrl);
            overrides.put(HIKARI_JDBC_URL, result.jdbcUrl);
            overrides.put(DS_DRIVER, "org.postgresql.Driver");

            // Set username and password if extracted and not already provided
            String existingUser = environment.getProperty(DS_USERNAME);
            String existingPass = environment.getProperty(DS_PASSWORD);
            if (!hasText(existingUser) && hasText(result.username)) {
                overrides.put(DS_USERNAME, result.username);
            }
            if (!hasText(existingPass) && hasText(result.password)) {
                overrides.put(DS_PASSWORD, result.password);
            }

            MutablePropertySources sources = environment.getPropertySources();
            // Highest precedence so these values win over application.yml
            sources.addFirst(new MapPropertySource("databaseUrlProcessor", overrides));
        } catch (RuntimeException e) {
            // Leave the value as-is; Spring will surface connection errors if invalid
        }
    }

    @Override
    public int getOrder() {
        // Run early but after default property source setup
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

