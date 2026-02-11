package net.findmybook.adapters.persistence;

import jakarta.annotation.Nullable;
import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Repository abstraction for persisting non-book page view events.
 *
 * <p>Use {@link net.findmybook.service.RecentBookViewRepository} for per-book detail views. This
 * repository is scoped to generic route-level analytics events such as homepage traffic.</p>
 */
@Repository
public class PageViewEventRepository {

    private static final Logger log = LoggerFactory.getLogger(PageViewEventRepository.class);

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the repository.
     *
     * @param jdbcTemplate JDBC helper used to persist page view rows; nullable for no-db profiles
     */
    public PageViewEventRepository(@Nullable JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Indicates whether persistence is available for page view writes.
     *
     * @return {@code true} when a JDBC datasource is configured
     */
    public boolean isEnabled() {
        return jdbcTemplate != null;
    }

    /**
     * Persists a page view event asynchronously.
     *
     * @param pageKey stable page key (for example: {@code homepage})
     * @param viewedAt timestamp for the event; {@link Instant#now()} when null
     * @param source optional source label (for example: {@code api})
     */
    @Async
    public void recordView(String pageKey, @Nullable Instant viewedAt, @Nullable String source) {
        if (!isEnabled() || !StringUtils.hasText(pageKey)) {
            return;
        }

        Instant effectiveInstant = viewedAt != null ? viewedAt : Instant.now();

        try {
            jdbcTemplate.update(
                "INSERT INTO page_view_events (page_key, viewed_at, source) VALUES (?, ?, ?)",
                ps -> {
                    ps.setString(1, pageKey.trim());
                    ps.setTimestamp(2, Timestamp.from(effectiveInstant));
                    if (!StringUtils.hasText(source)) {
                        ps.setNull(3, java.sql.Types.VARCHAR);
                    } else {
                        ps.setString(3, source);
                    }
                }
            );
        } catch (DataAccessException ex) {
            log.error("Failed to record page view for pageKey '{}': {}", pageKey, ex.getMessage(), ex);
            throw new IllegalStateException("Failed to record page view for pageKey '" + pageKey + "'", ex);
        }
    }
}
