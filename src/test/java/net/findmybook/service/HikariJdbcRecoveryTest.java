package net.findmybook.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.findmybook.repository.BookQueryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers
class HikariJdbcRecoveryTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "pgvector/pgvector:pg17"
    ).asCompatibleSubstituteFor("postgres");
    private static final Path REPOSITORY_ROOT = Path.of(".").toAbsolutePath().normalize();
    private static final Path CANONICAL_SCHEMA = REPOSITORY_ROOT.resolve("src/main/resources/schema.sql");
    private static final Path MIGRATIONS_DIRECTORY = REPOSITORY_ROOT.resolve("migrations");
    private static final String POOL_APPLICATION_NAME = "issue-118-hikari-recovery";
    private static final String RUNTIME_ROLE = "issue_118_runtime";
    private static final String RUNTIME_PASSWORD = "findmybook";
    private static final Duration STALE_CONNECTION_BYPASS = Duration.ofMillis(600);
    private static final Duration ACQUISITION_BUDGET = Duration.ofSeconds(2);
    private static final Duration ACQUISITION_ASSERTION_TOLERANCE = Duration.ofMillis(250);

    @Container
    private static final PostgreSQLContainer POSTGRES = postgresContainer();

    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private BookQueryRepository bookQueryRepository;
    private TestClock clock;
    private OutboxRelay outboxRelay;
    private UUID bookId;

    @BeforeAll
    static void installCanonicalDisplayQueriesAndRuntimeRole() throws Exception {
        Process installer = new ProcessBuilder(
            "make",
            "db-apply-display-queries",
            "PSQL=docker exec -i " + POSTGRES.getContainerId() + " psql -U " + POSTGRES.getUsername()
                + " -d " + POSTGRES.getDatabaseName()
        ).redirectErrorStream(true).start();
        String installerOutput = new String(installer.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(installer.waitFor()).as(installerOutput).isZero();

        try (Connection adminConnection = POSTGRES.createConnection("")) {
            JdbcTemplate adminJdbcTemplate = new JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(adminConnection, true)
            );
            adminJdbcTemplate.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            adminJdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
            adminJdbcTemplate.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + RUNTIME_ROLE);
            adminJdbcTemplate.execute("GRANT UPDATE ON events_outbox TO " + RUNTIME_ROLE);
            adminJdbcTemplate.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + RUNTIME_ROLE);
            adminJdbcTemplate.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + RUNTIME_ROLE);
        }
    }

    @BeforeEach
    void setUp() {
        dataSource = applicationPool();
        jdbcTemplate = new JdbcTemplate(dataSource);
        bookQueryRepository = new BookQueryRepository(jdbcTemplate, new ObjectMapper(), false, "");
        clock = new TestClock(Instant.parse("2026-09-08T00:00:00Z"));
        outboxRelay = new OutboxRelay(jdbcTemplate, mock(SimpMessagingTemplate.class), clock);
        bookId = UUID.randomUUID();
        insertBookFixture(bookId);
        waitForPoolConnections(Duration.ofSeconds(10));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void should_ReplaceTerminatedPooledConnections_When_BookQueriesAndOutboxOperationsRun() {
        assertThat(dataSource.getConnectionTimeout()).isEqualTo(ACQUISITION_BUDGET.toMillis());

        try (Connection adminConnection = POSTGRES.createConnection("")) {
            JdbcTemplate adminJdbcTemplate = new JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(adminConnection, true)
            );

            assertThat(bookQueryRepository.fetchBookDetail(bookId)).isPresent();
            assertReplacementAfterTermination(adminJdbcTemplate, () ->
                assertThat(bookQueryRepository.fetchBookDetail(bookId)).isPresent()
            );

            UUID eventId = enqueueEvent(adminJdbcTemplate);
            assertReplacementAfterTermination(adminJdbcTemplate, outboxRelay::relayEvents);
            assertThat(adminJdbcTemplate.queryForObject(
                "SELECT sent_at IS NOT NULL FROM events_outbox WHERE event_id=?", Boolean.class, eventId)).isTrue();
            adminJdbcTemplate.update("DELETE FROM events_outbox WHERE event_id=?", eventId);

            assertReplacementAfterTermination(adminJdbcTemplate, () -> assertThat(outboxRelay.getOutboxStats())
                .extracting(OutboxRelay.OutboxStats::unsent, OutboxRelay.OutboxStats::sent)
                .containsExactly(0, 0));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to create the isolated PostgreSQL admin connection", exception);
        }
    }

    @Test
    void should_FailWithinAcquisitionBudgetAndRecover_When_ReplacementsAreUnavailable() {
        try (Connection adminConnection = POSTGRES.createConnection("")) {
            JdbcTemplate adminJdbcTemplate = new JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(adminConnection, true)
            );
            UUID eventId = enqueueEvent(adminJdbcTemplate);
            adminJdbcTemplate.execute("ALTER ROLE " + RUNTIME_ROLE + " CONNECTION LIMIT 0");
            try {
                terminatePoolBackends(adminJdbcTemplate);
                awaitStaleConnectionDetectionWindow();

                Instant relayStartedAt = Instant.now();
                List<ILoggingEvent> relayLogs = captureRelayLogs(() -> assertThatCode(outboxRelay::relayEvents)
                    .doesNotThrowAnyException());
                assertThat(Duration.between(relayStartedAt, Instant.now()))
                    .isLessThanOrEqualTo(ACQUISITION_BUDGET.plus(ACQUISITION_ASSERTION_TOLERANCE));
                assertThat(relayLogs)
                    .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("Outbox relay database access failed; pausing polls for 5000 ms");

                assertFailsWithinAcquisitionBudget(
                    () -> bookQueryRepository.fetchBookDetail(bookId), DataAccessResourceFailureException.class
                );
                assertFailsWithinAcquisitionBudget(outboxRelay::getOutboxStats, IllegalStateException.class);
            } finally {
                adminJdbcTemplate.execute("ALTER ROLE " + RUNTIME_ROLE + " CONNECTION LIMIT -1");
            }

            Duration recoveryTime = waitForPoolConnections(Duration.ofSeconds(15));
            assertThat(recoveryTime).isLessThanOrEqualTo(Duration.ofSeconds(15));
            assertThat(bookQueryRepository.fetchBookDetail(bookId)).isPresent();
            clock.advanceBy(Duration.ofSeconds(5));
            assertThatCode(outboxRelay::relayEvents).doesNotThrowAnyException();
            assertThat(adminJdbcTemplate.queryForObject(
                "SELECT sent_at IS NOT NULL FROM events_outbox WHERE event_id=?", Boolean.class, eventId)).isTrue();
            adminJdbcTemplate.update("DELETE FROM events_outbox WHERE event_id=?", eventId);
            assertThat(outboxRelay.getOutboxStats())
                .extracting(OutboxRelay.OutboxStats::unsent, OutboxRelay.OutboxStats::sent)
                .containsExactly(0, 0);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to create the isolated PostgreSQL admin connection", exception);
        }
    }

    private void assertReplacementAfterTermination(JdbcTemplate adminJdbcTemplate, Runnable operation) {
        Set<Integer> terminatedBackendPids = terminatePoolBackends(adminJdbcTemplate);
        awaitStaleConnectionDetectionWindow();

        operation.run();

        assertThat(poolBackendPids(adminJdbcTemplate))
            .isNotEmpty()
            .doesNotContainAnyElementsOf(terminatedBackendPids);
    }

    private UUID enqueueEvent(JdbcTemplate adminJdbcTemplate) {
        UUID eventId = UUID.randomUUID();
        adminJdbcTemplate.update(
            "INSERT INTO events_outbox(event_id,topic,payload) VALUES (?, '/topic/recovery-test', '{}'::jsonb)",
            eventId);
        return eventId;
    }

    private void assertFailsWithinAcquisitionBudget(Runnable operation, Class<? extends Throwable> expectedFailure) {
        Instant startedAt = Instant.now();

        assertThatThrownBy(operation::run)
            .isInstanceOf(expectedFailure);

        assertThat(Duration.between(startedAt, Instant.now()))
            .isLessThanOrEqualTo(ACQUISITION_BUDGET.plus(ACQUISITION_ASSERTION_TOLERANCE));
    }

    private Set<Integer> terminatePoolBackends(JdbcTemplate adminJdbcTemplate) {
        int returnedConnectionPid = returnedPoolConnectionPid();
        Set<Integer> pooledBackendPids = poolBackendPids(adminJdbcTemplate);
        assertThat(pooledBackendPids).contains(returnedConnectionPid);
        pooledBackendPids.forEach(backendPid -> assertThat(adminJdbcTemplate.queryForObject(
            "SELECT pg_terminate_backend(?)", Boolean.class, backendPid
        )).isTrue());
        return pooledBackendPids;
    }

    private Set<Integer> poolBackendPids(JdbcTemplate adminJdbcTemplate) {
        return Set.copyOf(adminJdbcTemplate.query(
            "SELECT pid FROM pg_stat_activity WHERE application_name = ?",
            (resultSet, rowNumber) -> resultSet.getInt("pid"),
            POOL_APPLICATION_NAME
        ));
    }

    private int returnedPoolConnectionPid() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to capture the returned pooled PostgreSQL backend PID", exception);
        }
    }

    private void insertBookFixture(UUID fixtureBookId) {
        try (Connection adminConnection = POSTGRES.createConnection("")) {
            new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(adminConnection, true)).update(
                "INSERT INTO books(id, title, slug) VALUES (?, ?, ?)",
                fixtureBookId,
                "Hikari recovery fixture",
                "hikari-recovery-" + fixtureBookId
            );
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to insert the isolated book fixture", exception);
        }
    }

    private Duration waitForPoolConnections(Duration timeout) {
        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (dataSource.getHikariPoolMXBean().getIdleConnections() >= dataSource.getMinimumIdle()) {
                return Duration.between(startedAt, Instant.now());
            }
            await(Duration.ofMillis(50));
        }
        throw new IllegalStateException("Hikari did not restore the configured minimum idle connections");
    }

    private void awaitStaleConnectionDetectionWindow() {
        await(STALE_CONNECTION_BYPASS);
    }

    private void await(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Hikari stale-connection boundary", exception);
        }
    }

    private List<ILoggingEvent> captureRelayLogs(Runnable operation) {
        Logger relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
        boolean originalAdditivity = relayLogger.isAdditive();
        Level originalLevel = relayLogger.getLevel();
        relayLogger.setAdditive(false);
        relayLogger.setLevel(Level.DEBUG);
        logEvents.start();
        relayLogger.addAppender(logEvents);

        try {
            operation.run();
            return List.copyOf(logEvents.list);
        } finally {
            relayLogger.detachAppender(logEvents);
            logEvents.stop();
            relayLogger.setAdditive(originalAdditivity);
            relayLogger.setLevel(originalLevel);
        }
    }

    private HikariDataSource applicationPool() {
        HikariConfig configuration = hikariConfigurationFromApplicationYaml();
        configuration.setJdbcUrl(POSTGRES.getJdbcUrl());
        configuration.setUsername(RUNTIME_ROLE);
        configuration.setPassword(RUNTIME_PASSWORD);
        configuration.addDataSourceProperty("ApplicationName", POOL_APPLICATION_NAME);
        return new HikariDataSource(configuration);
    }

    private HikariConfig hikariConfigurationFromApplicationYaml() {
        try {
            var propertySources = new YamlPropertySourceLoader().load(
                "application", new ClassPathResource("application.yml"));
            var environment = new MockEnvironment();
            environment.getPropertySources().addFirst(propertySources.getFirst());
            var configuration = new HikariConfig();
            Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(configuration));
            return configuration;
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to load the canonical Hikari configuration", exception);
        }
    }

    private static PostgreSQLContainer postgresContainer() {
        PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("findmybook")
            .withUsername("findmybook")
            .withPassword("findmybook");
        postgres.withCopyToContainer(
            MountableFile.forHostPath(CANONICAL_SCHEMA),
            "/docker-entrypoint-initdb.d/000-schema.sql"
        );
        postgres.withCopyToContainer(MountableFile.forHostPath(MIGRATIONS_DIRECTORY), "/migrations");
        return postgres;
    }

    private static final class TestClock extends Clock {
        private Instant instant;

        private TestClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceBy(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
