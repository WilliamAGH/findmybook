package net.findmybook.repository;

import net.findmybook.util.ValidationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class BookQueryResultSetSupport {
    private static final Logger log = LoggerFactory.getLogger(BookQueryResultSetSupport.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * Cache column labels per {@link ResultSet} to avoid recalculating metadata for every column lookup.
     * Weak keys ensure entries disappear once the driver releases the result set.
     */
    private final Map<ResultSet, Set<String>> columnMetadataCache =
        Collections.synchronizedMap(new WeakHashMap<>());

    private final ObjectMapper objectMapper;

    BookQueryResultSetSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<String> parseTextArray(java.sql.Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }

        String[] array = (String[]) sqlArray.getArray();
        return array == null ? List.of() : Arrays.asList(array);
    }

    Map<String, Object> parseJsonb(String jsonb) {
        if (jsonb == null || jsonb.isBlank() || jsonb.equals("{}")) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(jsonb, MAP_TYPE);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Corrupted JSONB data cannot be parsed: " + ex.getMessage(), ex);
        }
    }

    Double getDoubleOrNull(ResultSet rs, String columnName) throws SQLException {
        if (!hasColumn(rs, columnName)) {
            return null;
        }
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }

    Integer getIntOrNull(ResultSet rs, String columnName) throws SQLException {
        if (!hasColumn(rs, columnName)) {
            return null;
        }
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

    Boolean getBooleanOrNull(ResultSet rs, String columnName) throws SQLException {
        if (!hasColumn(rs, columnName)) {
            return null;
        }
        boolean value = rs.getBoolean(columnName);
        return rs.wasNull() ? null : value;
    }

    LocalDate getLocalDateOrNull(ResultSet rs, String columnName) throws SQLException {
        if (!hasColumn(rs, columnName)) {
            return null;
        }
        Date date = rs.getDate(columnName);
        return date == null ? null : date.toLocalDate();
    }

    String getStringOrNull(ResultSet rs, String columnName) throws SQLException {
        return hasColumn(rs, columnName) ? rs.getString(columnName) : null;
    }

    private boolean hasColumn(ResultSet rs, String columnName) throws SQLException {
        Set<String> columnLabels = columnMetadataCache.get(rs);
        if (columnLabels == null) {
            columnLabels = loadColumnLabels(rs);
            columnMetadataCache.put(rs, columnLabels);
        }
        return columnLabels.contains(columnName.toLowerCase(Locale.ROOT));
    }

    private Set<String> loadColumnLabels(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        Set<String> labels = new HashSet<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = metaData.getColumnLabel(i);
            if (ValidationUtils.hasText(label)) {
                labels.add(label.toLowerCase(Locale.ROOT));
            }
        }
        return labels;
    }
}
