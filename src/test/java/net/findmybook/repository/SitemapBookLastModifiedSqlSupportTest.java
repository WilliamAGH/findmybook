package net.findmybook.repository;

import net.findmybook.config.CacheComponentsConfig;
import net.findmybook.config.SitemapProperties;
import net.findmybook.service.SitemapService;
import net.findmybook.support.sitemap.SitemapBookLastModifiedSqlSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class SitemapBookLastModifiedSqlSupportTest {

    private static final String PARALLEL_WORKER_SETTING = "max_parallel_workers_per_gather";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Test
    @DisplayName("globalBookLastModifiedCte injects alias and leaves no unresolved placeholders")
    void should_RenderGlobalBookLastModifiedCte_When_AliasProvided() {
        String sql = SitemapBookLastModifiedSqlSupport.globalBookLastModifiedCte("book_updated_at");

        assertTrue(sql.contains("MAX(change_events.changed_at) AS book_updated_at"));
        assertTrue(sql.contains("FROM book_ai_content bac"));
        assertTrue(sql.contains("FROM book_seo_metadata bsm"));
        assertFalse(sql.contains("%s"));
    }

    @Test
    @DisplayName("scopedAuthorBookLastModifiedQuery injects placeholders and alias without unresolved placeholders")
    void should_RenderScopedAuthorBookLastModifiedQuery_When_PlaceholdersAndAliasProvided() {
        String sql = SitemapBookLastModifiedSqlSupport.scopedAuthorBookLastModifiedQuery("?, ?, ?", "book_updated_at");

        assertTrue(sql.contains("WHERE baj.author_id IN (?, ?, ?)"));
        assertTrue(sql.contains("MAX(change_events.changed_at) AS book_updated_at"));
        assertTrue(sql.contains("SELECT baj.author_id, blm.id, blm.slug, blm.title, blm.book_updated_at"));
        assertFalse(sql.contains("%s"));
    }

    @Test
    void should_PageBooksBeforeAggregatingChangeEvents_When_RenderingXmlQuery() {
        String sql = SitemapBookLastModifiedSqlSupport.pagedBookLastModifiedQuery("book_updated_at");

        assertThat(sql)
            .contains("requested_books AS MATERIALIZED")
            .contains("LIMIT ? OFFSET ?")
            .contains("change_events AS NOT MATERIALIZED")
            .contains("LEFT JOIN change_events ON change_events.book_id = rb.book_id")
            .contains("MAX(change_events.changed_at) AS book_updated_at")
            .doesNotContain("%s");
        assertThat(sql.split("JOIN requested_books rb", -1)).hasSize(12);
        assertThat(sql.indexOf("LIMIT ? OFFSET ?")).isLessThan(sql.indexOf("change_events AS NOT MATERIALIZED"));
    }

    @Test
    void should_UseOneGlobalAggregateWithoutPerBookGrouping_When_RenderingFingerprintQuery() {
        String sql = SitemapBookLastModifiedSqlSupport.bookFingerprintQuery("book_updated_at");

        assertThat(sql)
            .contains("SELECT (SELECT COUNT(*) FROM requested_books) AS total_records")
            .contains("MAX(change_events.changed_at)")
            .contains("AS book_updated_at")
            .doesNotContain("book_last_modified")
            .doesNotContain("GROUP BY");
    }

    @Test
    void should_ThrowIllegalArgument_When_AliasContainsSqlInjection() {
        assertThatThrownBy(() ->
            SitemapBookLastModifiedSqlSupport.globalBookLastModifiedCte("x; DROP TABLE books"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_ThrowIllegalArgument_When_AliasIsBlank() {
        assertThatThrownBy(() ->
            SitemapBookLastModifiedSqlSupport.globalBookLastModifiedCte(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_ThrowIllegalArgument_When_PlaceholdersContainSqlInjection() {
        assertThatThrownBy(() ->
            SitemapBookLastModifiedSqlSupport.scopedAuthorBookLastModifiedQuery(
                "1; DROP TABLE books --", "book_updated_at"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_AcceptValidAlias_When_AliasIsSimpleIdentifier() {
        assertDoesNotThrow(() ->
            SitemapBookLastModifiedSqlSupport.globalBookLastModifiedCte("book_updated_at"));
    }

    @Test
    void should_DisableParallelWorkersBeforeAggregate_When_AllBooksAreCounted() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SitemapRepository sitemapRepository = new SitemapRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(12);

        assertThat(sitemapRepository.countAllBooks()).isEqualTo(12);

        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).execute("SET LOCAL max_parallel_workers_per_gather = 0");
        inOrder.verify(jdbcTemplate).queryForObject(anyString(), eq(Integer.class));
    }

    @Test
    void should_DisableParallelWorkersBeforeAggregate_When_BooksAreCountedByBucket() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SitemapRepository sitemapRepository = new SitemapRepository(jdbcTemplate);
        Map<String, Integer> expected = Map.of("A", 12);
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Map<String, Integer>>>any()
        )).thenReturn(expected);

        assertThat(sitemapRepository.countBooksByBucket()).isEqualTo(expected);

        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).execute("SET LOCAL max_parallel_workers_per_gather = 0");
        inOrder.verify(jdbcTemplate).query(
                anyString(),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Map<String, Integer>>>any()
        );
    }

    @Test
    void should_DisableParallelWorkersBeforeAggregate_When_AuthorsAreCountedByBucket() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SitemapRepository sitemapRepository = new SitemapRepository(jdbcTemplate);
        Map<String, Integer> expected = Map.of("B", 7);
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Map<String, Integer>>>any()
        )).thenReturn(expected);

        assertThat(sitemapRepository.countAuthorsByBucket()).isEqualTo(expected);

        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).execute("SET LOCAL max_parallel_workers_per_gather = 0");
        inOrder.verify(jdbcTemplate).query(
                anyString(),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Map<String, Integer>>>any()
        );
    }

    @Test
    void should_SelectCanonicalOrderedListings_When_AuthorListingMetadataIsRequested() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SitemapRepository sitemapRepository = new SitemapRepository(jdbcTemplate);
        List<SitemapRepository.AuthorListingMetadata> expected = List.of(
                new SitemapRepository.AuthorListingMetadata(
                        "A",
                        1,
                        Optional.of(Instant.parse("2024-02-01T00:00:00Z"))
                )
        );
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.AuthorListingMetadata>>any(),
                eq(100)
        )).thenReturn(expected);

        assertThat(sitemapRepository.fetchAuthorListingMetadata(100)).isEqualTo(expected);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).execute("SET LOCAL max_parallel_workers_per_gather = 0");
        inOrder.verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.AuthorListingMetadata>>any(),
                eq(100)
        );
        assertThat(sqlCaptor.getValue())
                .contains("book_last_modified")
                .contains("ranked_authors")
                .contains("author_listing_pages")
                .contains("ROW_NUMBER() OVER")
                .contains("ORDER BY CASE bucket")
                .contains("SELECT bucket, author_page_number, last_modified")
                .doesNotContain("GROUP BY page_number")
                .doesNotContain("%s");
    }

    @Test
    void should_DisableParallelWorkersBeforeQuery_When_BookPageMetadataIsRequested() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SitemapRepository sitemapRepository = new SitemapRepository(jdbcTemplate);
        List<SitemapRepository.PageMetadata> expected = List.of(
                new SitemapRepository.PageMetadata(1, Instant.parse("2024-02-01T00:00:00Z"))
        );
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.PageMetadata>>any(),
                eq(5000)
        )).thenReturn(expected);

        assertThat(sitemapRepository.fetchBookPageMetadata(5000)).isEqualTo(expected);

        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).execute("SET LOCAL max_parallel_workers_per_gather = 0");
        inOrder.verify(jdbcTemplate).query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.PageMetadata>>any(),
                eq(5000)
        );
    }

    @Test
    void should_UseBoundedPageQuery_When_BookXmlPageIsRequested() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SitemapRepository sitemapRepository = new SitemapRepository(jdbcTemplate);
        when(jdbcTemplate.query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.BookRow>>any(),
            eq(5000),
            eq(10000)
        )).thenReturn(List.of());

        sitemapRepository.fetchBooksForXml(5000, 10000);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.BookRow>>any(),
            eq(5000),
            eq(10000)
        );
        assertThat(sqlCaptor.getValue())
            .contains("requested_books AS MATERIALIZED")
            .contains("LIMIT ? OFFSET ?")
            .doesNotContain("FROM book_last_modified ORDER BY");
    }

    @Test
    void should_DisableParallelWorkersForTransaction_When_BookFingerprintIsRequested() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SitemapRepository sitemapRepository = new SitemapRepository(jdbcTemplate);
        SitemapRepository.DatasetFingerprint expected = new SitemapRepository.DatasetFingerprint(
            42,
            Instant.parse("2026-07-15T00:00:00Z")
        );
        when(jdbcTemplate.queryForObject(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.DatasetFingerprint>>any()
        )).thenReturn(expected);

        assertThat(sitemapRepository.fetchBookFingerprint()).isEqualTo(expected);

        verify(jdbcTemplate).execute("SET LOCAL max_parallel_workers_per_gather = 0");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(
            sqlCaptor.capture(),
            org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.DatasetFingerprint>>any()
        );
        assertThat(sqlCaptor.getValue())
            .contains("MAX(change_events.changed_at)")
            .doesNotContain("GROUP BY");
    }

    @Test
    void should_DisableParallelWorkersBeforeQuery_When_AuthorFingerprintIsRequested() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SitemapRepository sitemapRepository = new SitemapRepository(jdbcTemplate);
        SitemapRepository.DatasetFingerprint expected = new SitemapRepository.DatasetFingerprint(
            17,
            Instant.parse("2026-07-16T00:00:00Z")
        );
        when(jdbcTemplate.queryForObject(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.DatasetFingerprint>>any()
        )).thenReturn(expected);

        assertThat(sitemapRepository.fetchAuthorFingerprint()).isEqualTo(expected);

        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).execute("SET LOCAL max_parallel_workers_per_gather = 0");
        inOrder.verify(jdbcTemplate).queryForObject(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.DatasetFingerprint>>any()
        );
    }

    @Nested
    class PostgreSqlTransactionBoundaryTest {

        private SingleConnectionDataSource dataSource;
        private RecordingJdbcTemplate jdbcTemplate;
        private SitemapService sitemapService;
        private String originalParallelWorkerSetting;

        @BeforeEach
        void setUpPostgreSqlBoundary() {
            dataSource = new SingleConnectionDataSource(
                    POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword(),
                    true
            );
            jdbcTemplate = new RecordingJdbcTemplate(dataSource);
            jdbcTemplate.execute("CREATE TEMP TABLE books (slug text)");
            jdbcTemplate.update("INSERT INTO books (slug) VALUES ('book-one')");
            originalParallelWorkerSetting = jdbcTemplate.queryForObject(
                    "SHOW " + PARALLEL_WORKER_SETTING,
                    String.class
            );
            jdbcTemplate.clearObservations();

            SitemapProperties properties = new SitemapProperties();
            CacheManager cacheManager = new CacheComponentsConfig().sitemapCacheManager(properties);
            if (cacheManager instanceof SimpleCacheManager simpleCacheManager) {
                simpleCacheManager.initializeCaches();
            }
            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            StaticListableBeanFactory transactionManagerFactory = new StaticListableBeanFactory();
            transactionManagerFactory.addBean("transactionManager", transactionManager);
            sitemapService = new SitemapService(
                    new SitemapRepository(jdbcTemplate),
                    properties,
                    cacheManager,
                    transactionManagerFactory.getBeanProvider(PlatformTransactionManager.class)
            );
        }

        @AfterEach
        void closePostgreSqlConnection() {
            dataSource.destroy();
        }

        @Test
        void should_RestoreParallelWorkerSettingAfterCommit_When_AggregateSucceeds() {
            assertThat(sitemapService.getBooksXmlPageCount()).isEqualTo(1);

            assertGuardAndAggregateSharedReadOnlyTransaction();
            assertThat(currentParallelWorkerSetting()).isEqualTo(originalParallelWorkerSetting);
        }

        @Test
        void should_RestoreParallelWorkerSettingAfterRollback_When_AggregateFails() {
            jdbcTemplate.execute("DROP TABLE books");
            jdbcTemplate.clearObservations();

            assertThatThrownBy(sitemapService::getBooksXmlPageCount)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("book XML page count");

            assertGuardAndAggregateSharedReadOnlyTransaction();
            assertThat(currentParallelWorkerSetting()).isEqualTo(originalParallelWorkerSetting);
        }

        private void assertGuardAndAggregateSharedReadOnlyTransaction() {
            StatementObservation guard = jdbcTemplate.observationFor(
                    "SET LOCAL " + PARALLEL_WORKER_SETTING + " = 0"
            );
            StatementObservation aggregate = jdbcTemplate.observationFor(
                    "SELECT COUNT(*) FROM books WHERE slug IS NOT NULL"
            );
            assertThat(guard.transactionActive()).isTrue();
            assertThat(aggregate.transactionActive()).isTrue();
            assertThat(guard.connectionTransactionBound()).isTrue();
            assertThat(aggregate.connectionTransactionBound()).isTrue();
            assertThat(guard.transactionReadOnly()).isTrue();
            assertThat(aggregate.transactionReadOnly()).isTrue();
            assertThat(aggregate.backendProcessId()).isEqualTo(guard.backendProcessId());
            assertThat(aggregate.parallelWorkerSetting()).isEqualTo("0");
        }

        private String currentParallelWorkerSetting() {
            return jdbcTemplate.queryForObject("SHOW " + PARALLEL_WORKER_SETTING, String.class);
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private final List<StatementObservation> observations = new ArrayList<>();

        private RecordingJdbcTemplate(SingleConnectionDataSource dataSource) {
            super(dataSource);
        }

        @Override
        public void execute(String sql) {
            recordObservation(sql);
            super.execute(sql);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            recordObservation(sql);
            return super.queryForObject(sql, requiredType);
        }

        private void clearObservations() {
            observations.clear();
        }

        private StatementObservation observationFor(String sql) {
            return observations.stream()
                    .filter(observation -> observation.sql().equals(sql))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("SQL was not observed: " + sql));
        }

        private void recordObservation(String sql) {
            DataSource configuredDataSource = Objects.requireNonNull(getDataSource());
            Connection connection = DataSourceUtils.getConnection(configuredDataSource);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT pg_backend_pid(), " +
                         "current_setting('" + PARALLEL_WORKER_SETTING + "'), " +
                         "current_setting('transaction_read_only')"
                 )) {
                resultSet.next();
                observations.add(new StatementObservation(
                        sql,
                        resultSet.getInt(1),
                        resultSet.getString(2),
                        "on".equals(resultSet.getString(3)),
                        TransactionSynchronizationManager.isActualTransactionActive(),
                        DataSourceUtils.isConnectionTransactional(connection, configuredDataSource)
                ));
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to observe PostgreSQL transaction", exception);
            } finally {
                DataSourceUtils.releaseConnection(connection, configuredDataSource);
            }
        }
    }

    private record StatementObservation(String sql,
                                        int backendProcessId,
                                        String parallelWorkerSetting,
                                        boolean transactionReadOnly,
                                        boolean transactionActive,
                                        boolean connectionTransactionBound) {
    }
}
