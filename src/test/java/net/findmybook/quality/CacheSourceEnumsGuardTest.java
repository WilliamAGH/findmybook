package net.findmybook.quality;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard test to prevent reintroduction of cache-based cover source enums
 * (LOCAL_CACHE, S3_CACHE) in runtime code. Storage location must be tracked
 * via ImageDetails.storageLocation, not via CoverImageSource or ImageSourceName.
 *
 * Allowed occurrences:
 * - Enum declaration files themselves
 */
public class CacheSourceEnumsGuardTest {

    private static final Path SEARCH_FUNCTIONS_MIGRATION_PATH =
        Paths.get(normalize("migrations/27_search_functions.sql"));
    private static final Path CANONICAL_SCHEMA_PATH =
        Paths.get(normalize("src/main/resources/schema.sql"));
    private static final Path SLUG_FUNCTIONS_MIGRATION_PATH =
        Paths.get(normalize("migrations/28_slug_functions.sql"));

    private static final Set<String> ALLOWED_PATHS = Set.of(
        normalize("src/main/java/net/findmybook/model/image/CoverImageSource.java"),
        normalize("src/main/java/net/findmybook/model/image/ImageSourceName.java")
    );

    private static final List<String> FORBIDDEN_PATTERNS = List.of(
        "CoverImageSource.LOCAL_CACHE",
        "CoverImageSource.S3_CACHE",
        "ImageSourceName.LOCAL_CACHE",
        "ImageSourceName.S3_CACHE"
    );

    @Test
    void noCacheBasedSourceEnumUsageOutsideEnums() throws IOException {
        Path srcMain = Paths.get(normalize("src/main/java"));
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(srcMain)) {
            for (Path path : paths.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String rel = normalize(srcMain.getParent() == null ? path.toString() : srcMain.getParent().relativize(path).toString());
                if (ALLOWED_PATHS.contains(rel)) {
                    continue;
                }
                String content = Files.readString(path, StandardCharsets.UTF_8);
                for (String pattern : FORBIDDEN_PATTERNS) {
                    if (content.contains(pattern)) {
                        violations.add(rel + " contains forbidden pattern: " + pattern);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Forbidden cache-based source enum usage found:\n" + String.join("\n", violations));
    }

    @Test
    void should_UseQualifiedColumns_When_SearchFunctionsOrderFinalResults() throws IOException {
        String migrationSql = Files.readString(SEARCH_FUNCTIONS_MIGRATION_PATH, StandardCharsets.UTF_8);

        assertTrue(migrationSql.contains("from deduplicated d"),
            "search_books should alias deduplicated rows in its final select");
        assertTrue(migrationSql.contains("lower(d.title)"),
            "search_books should order using qualified title reference");
        assertTrue(migrationSql.contains("coalesce(d.cluster_id, d.book_id)"),
            "search_books should order using qualified cluster/book references");
        assertFalse(migrationSql.contains("lower(title),"),
            "search_books final ordering must not use ambiguous unqualified title reference");

        assertTrue(migrationSql.contains("from author_matches am"),
            "search_authors should alias author_matches rows in its final select");
        assertTrue(migrationSql.contains("lower(am.author_name)"),
            "search_authors should order using qualified author_name reference");
        assertFalse(migrationSql.contains("lower(author_name),"),
            "search_authors final ordering must not use ambiguous unqualified author_name reference");
    }

    @Test
    void should_RefuseFreshBootstrap_When_PublicSchemaContainsAnyRelation() throws IOException {
        String schemaSql = Files.readString(CANONICAL_SCHEMA_PATH, StandardCharsets.UTF_8);
        String existingRelationsGuard = """
              IF NOT authors_existed_at_entry
                AND NOT bootstrap_marker_existed_at_entry
                AND public_schema_had_relations_at_entry
              THEN
                RAISE EXCEPTION 'public schema contains relations but authors is absent; refusing fresh-bootstrap classification';
              END IF;
            """;

        assertTrue(schemaSql.contains("FROM pg_catalog.pg_class AS existing_relation"),
            "fresh-bootstrap detection must inspect the public namespace catalog");
        assertTrue(schemaSql.contains("WHERE existing_namespace.nspname = 'public'"),
            "fresh-bootstrap detection must cover every public relation without a mirrored table inventory");
        assertTrue(schemaSql.contains(existingRelationsGuard),
            "an existing relation must fail closed unless the durable bootstrap marker already exists");
        assertTrue(schemaSql.indexOf(existingRelationsGuard)
                < schemaSql.indexOf("CREATE TABLE public.findmybook_schema_bootstrap_state"),
            "the existing-relation guard must run before creating the durable bootstrap marker");
    }

    @Test
    void should_NotRestoreLegacySlugFunction_When_CanonicalAuthorContractIsApplied() throws IOException {
        String schemaSql = Files.readString(CANONICAL_SCHEMA_PATH, StandardCharsets.UTF_8);
        String slugMigrationSql = Files.readString(SLUG_FUNCTIONS_MIGRATION_PATH, StandardCharsets.UTF_8);

        assertTrue(slugMigrationSql.contains("BEGIN;"),
            "legacy slug replay and retirement must remain atomic");
        assertTrue(slugMigrationSql.contains(
                "to_regprocedure('public.canonical_author_contract_is_applied()') IS NOT NULL"),
            "slug replay must detect the durable canonical author contract");
        assertTrue(slugMigrationSql.contains(
                "DROP FUNCTION IF EXISTS public.ensure_unique_slug(text)"),
            "contracted databases must retire the counter-probing slug owner before commit");
        assertTrue(slugMigrationSql.indexOf("DROP FUNCTION IF EXISTS public.ensure_unique_slug(text)")
                < slugMigrationSql.lastIndexOf("COMMIT;"),
            "legacy slug retirement must occur before replay becomes visible");
        assertTrue(schemaSql.contains("canonical author contract has retired slug functions present"),
            "the schema orchestrator must fail if post-contract replay leaves a legacy slug owner");
    }

    private static String normalize(String p) {
        return p.replace('\\', '/');
    }
}
