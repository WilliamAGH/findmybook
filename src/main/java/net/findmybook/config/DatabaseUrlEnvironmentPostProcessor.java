package net.findmybook.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.HashMap;
import java.util.Map;

/**
 * Normalizes datasource URL values provided as Postgres URI (postgres://...)
 * into a JDBC URL (jdbc:postgresql://...).
 *
 * Also sets spring.datasource.username and spring.datasource.password from the URI
 * user-info if not already provided by higher-precedence sources.
 */
public final class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String DS_URL = "spring.datasource.url";
    private static final String ENV_DS_URL = "SPRING_DATASOURCE_URL";
    private static final String ENV_DATABASE_URL = "DATABASE_URL";
    private static final String ENV_POSTGRES_URL = "POSTGRES_URL";
    private static final String ENV_JDBC_DATABASE_URL = "JDBC_DATABASE_URL";
    private static final String DS_JDBC_URL = "spring.datasource.jdbc-url";
    private static final String HIKARI_JDBC_URL = "spring.datasource.hikari.jdbc-url";
    private static final String DS_USERNAME = "spring.datasource.username";
    private static final String DS_PASSWORD = "spring.datasource.password";
    private static final String DS_DRIVER = "spring.datasource.driver-class-name";
    private static final String POSTGRES_DRIVER_CLASS = "org.postgresql.Driver";
    private static final String DATABASE_URL_PROCESSOR_PROPERTY_SOURCE = "databaseUrlProcessor";
    private static final String JDBC_POSTGRESQL_PREFIX = "jdbc:postgresql://";
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_DATABASE = "postgres";
    private static final int DEFAULT_PORT = 5432;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /**
     * Checks if a string has meaningful content (not null, not empty, not blank).
     */
    private static boolean hasText(String candidate) {
        return candidate != null && !candidate.isBlank();
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String candidate : values) {
            if (hasText(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isJdbcPostgresUrl(String candidate) {
        return hasText(candidate) && candidate.trim().toLowerCase(Locale.ROOT).startsWith(JDBC_POSTGRESQL_PREFIX);
    }

    private static DatasourceOverrides createDatasourceOverrides(String jdbcUrl) {
        return DatasourceOverrides.forJdbcUrl(jdbcUrl);
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
     * Typed datasource override values applied before Spring DataSource binding.
     */
    private record DatasourceOverrides(String datasourceUrl,
                                       String datasourceJdbcUrl,
                                       String hikariJdbcUrl,
                                       String driverClassName,
                                       String datasourceUsername,
                                       String datasourcePassword) {

        private static DatasourceOverrides forJdbcUrl(String jdbcUrl) {
            return new DatasourceOverrides(
                jdbcUrl,
                jdbcUrl,
                jdbcUrl,
                POSTGRES_DRIVER_CLASS,
                null,
                null
            );
        }

        private DatasourceOverrides withCredentials(String username, String password) {
            return new DatasourceOverrides(
                datasourceUrl,
                datasourceJdbcUrl,
                hikariJdbcUrl,
                driverClassName,
                username,
                password
            );
        }

        private Map<String, Object> toPropertyMap() {
            Map<String, Object> properties = new HashMap<>();
            properties.put(DS_URL, datasourceUrl);
            properties.put(DS_JDBC_URL, datasourceJdbcUrl);
            properties.put(HIKARI_JDBC_URL, hikariJdbcUrl);
            properties.put(DS_DRIVER, driverClassName);
            if (hasText(datasourceUsername)) {
                properties.put(DS_USERNAME, datasourceUsername);
            }
            if (hasText(datasourcePassword)) {
                properties.put(DS_PASSWORD, datasourcePassword);
            }
            return properties;
        }
    }

    /**
     * Defaults empty host to localhost (PostgreSQL convention).
     */
    private static String defaultHost(String host) {
        return hasText(host) ? host : DEFAULT_HOST;
    }

    private static String decodeComponent(String value) {
        if (!hasText(value)) {
            return value;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            logBootstrapWarning("Failed to URL-decode component '" + value + "', preserving raw value");
            return value;
        }
    }

    private static String formatHostForJdbc(String host) {
        if (!hasText(host)) {
            return DEFAULT_HOST;
        }
        if (host.contains(":") && !(host.startsWith("[") && host.endsWith("]"))) {
            return "[" + host + "]";
        }
        return host;
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
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            logBootstrapWarning("Invalid datasource URI format; preserving original value");
            return java.util.Optional.empty();
        }
        String scheme = uri.getScheme();
        String lowerScheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (!("postgres".equals(lowerScheme) || "postgresql".equals(lowerScheme))) {
            return java.util.Optional.empty();
        }

        String username = null;
        String password = null;
        String rawUserInfo = uri.getRawUserInfo();
        if (hasText(rawUserInfo)) {
            int colonIndex = rawUserInfo.indexOf(':');
            if (colonIndex >= 0) {
                username = decodeComponent(rawUserInfo.substring(0, colonIndex));
                password = decodeComponent(rawUserInfo.substring(colonIndex + 1));
            } else {
                username = decodeComponent(rawUserInfo);
            }
        }

        String host = defaultHost(uri.getHost());
        int port = isValidPort(uri.getPort()) ? uri.getPort() : DEFAULT_PORT;
        if (!hasText(uri.getHost()) && hasText(uri.getRawAuthority())) {
            String authority = uri.getRawAuthority();
            int atIndex = authority.lastIndexOf('@');
            String hostPortPart = atIndex >= 0 ? authority.substring(atIndex + 1) : authority;
            HostPort parsedHostPort = parseHostPort(hostPortPart, DEFAULT_PORT);
            host = parsedHostPort.host();
            port = parsedHostPort.port();
        }

        String database = DEFAULT_DATABASE;
        String rawPath = uri.getRawPath();
        if (hasText(rawPath)) {
            String rawDatabase = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
            if (hasText(rawDatabase)) {
                database = decodeComponent(rawDatabase);
            }
        }

        String query = uri.getRawQuery();
        StringBuilder jdbc = new StringBuilder()
                .append("jdbc:postgresql://")
                .append(formatHostForJdbc(host))
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
        String url = firstText(
            environment.getProperty(DS_URL),
            environment.getProperty(ENV_DS_URL),
            environment.getProperty(ENV_DATABASE_URL),
            environment.getProperty(ENV_POSTGRES_URL),
            environment.getProperty(ENV_JDBC_DATABASE_URL)
        );
        if (!hasText(url)) {
            return;
        }

        String datasourceUrl = url.trim();

        try {
            if (isJdbcPostgresUrl(datasourceUrl)) {
                MutablePropertySources sources = environment.getPropertySources();
                DatasourceOverrides overrides = createDatasourceOverrides(datasourceUrl);
                sources.addFirst(new MapPropertySource(DATABASE_URL_PROCESSOR_PROPERTY_SOURCE, overrides.toPropertyMap()));
                return;
            }

            java.util.Optional<JdbcParseResult> parsed = normalizePostgresUrl(datasourceUrl);
            if (parsed.isEmpty()) {
                return;
            }

            JdbcParseResult result = parsed.get();
            DatasourceOverrides overrides = createDatasourceOverrides(result.jdbcUrl);

            // Set username and password if extracted and not already provided
            String existingUser = environment.getProperty(DS_USERNAME);
            String existingPass = environment.getProperty(DS_PASSWORD);
            String resolvedUsername = !hasText(existingUser) && hasText(result.username) ? result.username : null;
            String resolvedPassword = !hasText(existingPass) && hasText(result.password) ? result.password : null;
            overrides = overrides.withCredentials(resolvedUsername, resolvedPassword);

            MutablePropertySources sources = environment.getPropertySources();
            // Highest precedence so these values win over application.yml
            sources.addFirst(new MapPropertySource(DATABASE_URL_PROCESSOR_PROPERTY_SOURCE, overrides.toPropertyMap()));
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Failed to normalize datasource properties: " + e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        // Run early but after default property source setup
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
