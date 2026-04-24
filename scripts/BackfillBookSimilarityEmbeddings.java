///usr/bin/env java --enable-preview --source 25 "$0" "$@"; exit $?
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import net.findmybook.FindmybookApplication;
static final ObjectMapper JSON = new ObjectMapper();
static final Path PROFILE_CONTRACT = Path.of("src/main/resources/similarity/book-similarity-profiles.json");
static final String INPUT_FORMAT = "key_value";
static final String SECTION_FUSION_REGIME = "section_fusion";
static final List<String> FIELD_COLUMNS = List.of("title", "subtitle", "authors", "classification_tags", "collection_categories",
    "description", "ai_summary", "ai_reader_fit", "ai_key_themes", "ai_takeaways", "ai_context", "publisher",
    "published_year", "page_count", "language", "average_rating", "ratings_count");
static final Map<String, List<String>> SECTION_FIELDS = Map.of(
    "identity", List.of("title", "subtitle", "authors"),
    "classification", List.of("classification_tags", "collection_categories"),
    "description", List.of("description"),
    "ai_content", List.of("ai_summary", "ai_reader_fit", "ai_key_themes", "ai_takeaways", "ai_context"),
    "bibliographic", List.of("publisher", "published_year", "page_count", "language", "average_rating", "ratings_count")
);
void main(String[] args) throws Exception {
    Options options = parseOptions(args);
    FindmybookApplication.prepareExternalConfiguration();
    RuntimeConfig runtimeConfig = loadRuntimeConfig(options);
    SimilarityContract contract = loadContract();
    ProfileContract profile = activeProfile(contract, options.profileId());
    validateContract(contract, profile);
    try (Connection connection = openConnection(runtimeConfig)) {
        List<UUID> bookIds = resolveBookIds(connection, options);
        if (bookIds.isEmpty()) {
            System.out.println("No books matched the requested similarity backfill cohort.");
            return;
        }
        List<BookProfile> books = loadProfiles(connection, bookIds);
        List<SectionInput> sectionInputs = buildSectionInputs(contract, books);
        System.out.printf("Similarity cohort: books=%d sectionInputs=%d profile=%s%n", books.size(), sectionInputs.size(), profile.id());
        if (options.dryRun()) return;
        Map<SectionCacheKey, double[]> sectionVectors = loadCachedSectionVectors(connection, sectionInputs, runtimeConfig);
        List<SectionInput> missingInputs = sectionInputs.stream().filter(sectionInput -> !sectionVectors.containsKey(sectionInput.cacheKey())).toList();
        embedMissingSections(connection, missingInputs, sectionVectors, runtimeConfig, options.batchSize());
        int fusedCount = upsertFusedVectors(connection, contract, profile, books, sectionInputs, sectionVectors, runtimeConfig);
        System.out.printf("Similarity vectors upserted: %d%n", fusedCount);
        if (options.anchorIdentifier() != null) {
            printNearest(connection, resolveIdentifier(connection, options.anchorIdentifier()), contract, profile, runtimeConfig, options.top());
        }
    }
}
List<UUID> resolveBookIds(Connection connection, Options options) throws Exception {
    if (options.idFilePath() != null) {
        List<UUID> ids = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(options.idFilePath()))) if (hasText(line)) ids.add(resolveIdentifier(connection, line.trim()));
        return ids;
    }
    if (options.bookIdentifier() != null) return List.of(resolveIdentifier(connection, options.bookIdentifier()));
    if (options.anchorIdentifier() != null) {
        UUID anchorBookId = resolveIdentifier(connection, options.anchorIdentifier());
        BookProfile anchor = loadProfiles(connection, List.of(anchorBookId)).getFirst();
        return candidatePool(connection, anchorBookId, lexicalQuery(anchor), options.limit());
    }
    return defaultCohort(connection, options.limit());
}
UUID resolveIdentifier(Connection connection, String identifier) throws Exception {
    String sql = "select b.id from books b where b.id::text = ? or b.slug = ? or b.isbn10 = ? or b.isbn13 = ? order by b.updated_at desc limit 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (int index = 1; index <= 4; index += 1) statement.setString(index, identifier.trim());
        try (ResultSet rows = statement.executeQuery()) {
            if (rows.next()) return rows.getObject("id", UUID.class);
        }
    }
    throw new IllegalArgumentException("Could not resolve book identifier: " + identifier);
}
List<UUID> candidatePool(Connection connection, UUID anchorBookId, String lexicalQuery, int limit) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(readSql("candidate-pool-query.sql"))) {
        statement.setObject(1, anchorBookId); statement.setString(2, lexicalQuery); statement.setObject(3, anchorBookId);
        statement.setInt(4, Math.max(1, limit)); return readUuidList(statement);
    }
}
List<UUID> defaultCohort(Connection connection, int limit) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(readSql("default-cohort-query.sql"))) {
        statement.setInt(1, Math.max(1, limit)); return readUuidList(statement);
    }
}
List<BookProfile> loadProfiles(Connection connection, List<UUID> bookIds) throws Exception {
    if (bookIds.isEmpty()) return List.of();
    String sql = readSql("book-profile-query.sql").replace(":book_ids", placeholders(bookIds.size()));
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (int index = 0; index < bookIds.size(); index += 1) statement.setObject(index + 1, bookIds.get(index));
        try (ResultSet rows = statement.executeQuery()) {
            Map<UUID, BookProfile> profiles = new LinkedHashMap<>();
            while (rows.next()) {
                UUID bookId = rows.getObject("id", UUID.class);
                Map<String, String> fieldTexts = new LinkedHashMap<>();
                for (String fieldName : FIELD_COLUMNS) fieldTexts.put(fieldName, text(rows.getString(fieldName)));
                profiles.put(bookId, new BookProfile(bookId, fieldTexts));
            }
            return bookIds.stream().map(profiles::get).filter(profile -> profile != null).toList();
        }
    }
}
List<SectionInput> buildSectionInputs(SimilarityContract contract, List<BookProfile> books) {
    List<SectionInput> inputs = new ArrayList<>();
    for (BookProfile book : books) for (String section : contract.sectionOrder()) {
        String sectionText = sectionText(section, book);
        if (hasText(sectionText)) inputs.add(new SectionInput(book.bookId(), section, sha256(sectionText), sectionText, preview(sectionText)));
    }
    return inputs;
}
String sectionText(String section, BookProfile book) {
    List<String> lines = new ArrayList<>();
    for (String fieldName : SECTION_FIELDS.getOrDefault(section, List.of())) {
        String fieldText = book.fieldTexts().getOrDefault(fieldName, "");
        if (hasText(fieldText)) lines.add(fieldName + ": " + fieldText);
    }
    return String.join("\n", lines);
}
Map<SectionCacheKey, double[]> loadCachedSectionVectors(Connection connection, List<SectionInput> inputs, RuntimeConfig runtimeConfig) throws Exception {
    Map<SectionCacheKey, double[]> vectors = new LinkedHashMap<>();
    if (inputs.isEmpty()) return vectors;
    List<UUID> bookIds = inputs.stream().map(SectionInput::bookId).distinct().toList();
    String sql = "select book_id, section_key, input_hash, embedding::text embedding_text from book_embedding_sections "
        + "where model = ? and input_format = ? and book_id in (" + placeholders(bookIds.size()) + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, runtimeConfig.embeddingsModel()); statement.setString(2, INPUT_FORMAT);
        for (int index = 0; index < bookIds.size(); index += 1) statement.setObject(index + 3, bookIds.get(index));
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) vectors.put(new SectionCacheKey(rows.getObject("book_id", UUID.class), rows.getString("section_key"),
                rows.getString("input_hash")), parseVector(rows.getString("embedding_text")));
        }
    }
    return vectors;
}
void embedMissingSections(Connection connection, List<SectionInput> missingInputs, Map<SectionCacheKey, double[]> vectors,
                          RuntimeConfig runtimeConfig, int batchSize) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    for (int offset = 0; offset < missingInputs.size(); offset += batchSize) {
        List<SectionInput> batch = missingInputs.subList(offset, Math.min(offset + batchSize, missingInputs.size()));
        List<double[]> embeddings = requestEmbeddings(client, runtimeConfig, batch.stream().map(SectionInput::sectionText).toList());
        upsertSectionVectors(connection, runtimeConfig, batch, embeddings);
        for (int index = 0; index < batch.size(); index += 1) vectors.put(batch.get(index).cacheKey(), embeddings.get(index));
        System.out.printf("Embedded sections: %d/%d%n", Math.min(offset + batch.size(), missingInputs.size()), missingInputs.size());
    }
}
List<double[]> requestEmbeddings(HttpClient client, RuntimeConfig runtimeConfig, List<String> texts) throws Exception {
    ObjectNode payload = JSON.createObjectNode(); payload.put("model", runtimeConfig.embeddingsModel());
    ArrayNode input = payload.putArray("input"); texts.forEach(input::add);
    HttpRequest request = HttpRequest.newBuilder(URI.create(runtimeConfig.embeddingsUrl())).timeout(Duration.ofSeconds(120))
        .header("Authorization", "Bearer " + runtimeConfig.apiKey()).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload))).build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Embeddings request failed with status " + response.statusCode() + ": " + response.body());
    }
    List<double[]> embeddings = new ArrayList<>();
    for (JsonNode embeddingNode : JSON.readTree(response.body()).path("data")) embeddings.add(vectorFromJson(embeddingNode.path("embedding")));
    if (embeddings.size() != texts.size()) throw new IllegalStateException("Embedding response count mismatch.");
    return embeddings;
}
void upsertSectionVectors(Connection connection, RuntimeConfig runtimeConfig, List<SectionInput> batch, List<double[]> embeddings) throws Exception {
    String sql = "insert into book_embedding_sections (book_id, section_key, input_format, input_hash, model, embedding, input_preview) "
        + "values (?, ?, ?, ?, ?, ?::halfvec, ?) on conflict (book_id, section_key, model, input_format, input_hash) "
        + "do update set embedding = excluded.embedding, input_preview = excluded.input_preview, updated_at = now()";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (int index = 0; index < batch.size(); index += 1) {
            SectionInput sectionInput = batch.get(index);
            statement.setObject(1, sectionInput.bookId()); statement.setString(2, sectionInput.sectionKey());
            statement.setString(3, INPUT_FORMAT); statement.setString(4, sectionInput.inputHash());
            statement.setString(5, runtimeConfig.embeddingsModel()); statement.setString(6, vectorLiteral(embeddings.get(index)));
            statement.setString(7, sectionInput.inputPreview()); statement.addBatch();
        }
        statement.executeBatch();
    }
}
int upsertFusedVectors(Connection connection, SimilarityContract contract, ProfileContract profile, List<BookProfile> books,
                       List<SectionInput> inputs, Map<SectionCacheKey, double[]> sectionVectors, RuntimeConfig runtimeConfig) throws Exception {
    Map<UUID, List<SectionInput>> inputsByBook = new LinkedHashMap<>();
    inputs.forEach(sectionInput -> inputsByBook.computeIfAbsent(sectionInput.bookId(), ignored -> new ArrayList<>()).add(sectionInput));
    String sql = "insert into book_similarity_vectors (book_id, profile_id, profile_hash, model, model_version, input_format, "
        + "section_hash, section_input_hashes, embedding, qwen_4b_fp16, computed_at) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::halfvec, ?::halfvec, now()) "
        + "on conflict (book_id, model_version, profile_hash) do update set section_hash = excluded.section_hash, "
        + "section_input_hashes = excluded.section_input_hashes, embedding = excluded.embedding, qwen_4b_fp16 = excluded.qwen_4b_fp16, computed_at = now(), updated_at = now()";
    int fusedCount = 0; String profileHash = profileHash(contract, profile);
    String modelVersion = modelVersion(contract, profile, runtimeConfig.embeddingsModel());
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (BookProfile book : books) {
            FusedVector fused = fuseBook(contract, profile, inputsByBook.getOrDefault(book.bookId(), List.of()), sectionVectors);
            if (fused == null) continue;
            statement.setObject(1, book.bookId()); statement.setString(2, profile.id()); statement.setString(3, profileHash);
            statement.setString(4, runtimeConfig.embeddingsModel()); statement.setString(5, modelVersion);
            statement.setString(6, INPUT_FORMAT); statement.setString(7, fused.sectionHash());
            statement.setString(8, JSON.writeValueAsString(fused.sectionInputHashes())); statement.setString(9, vectorLiteral(fused.embedding()));
            statement.setString(10, vectorLiteral(fused.embedding()));
            statement.addBatch(); fusedCount += 1;
        }
        statement.executeBatch();
    }
    return fusedCount;
}
FusedVector fuseBook(SimilarityContract contract, ProfileContract profile, List<SectionInput> inputs, Map<SectionCacheKey, double[]> sectionVectors) {
    Map<String, SectionInput> inputsBySection = new LinkedHashMap<>();
    inputs.forEach(sectionInput -> inputsBySection.put(sectionInput.sectionKey(), sectionInput));
    List<String> activeSections = contract.sectionOrder().stream().filter(inputsBySection::containsKey).toList();
    if (activeSections.isEmpty()) return null;
    double totalWeight = activeSections.stream().mapToDouble(section -> profile.weights().get(section)).sum();
    double[] fused = null; Map<String, String> sectionHashes = new LinkedHashMap<>();
    for (String section : activeSections) {
        SectionInput sectionInput = inputsBySection.get(section);
        double[] normalized = normalize(sectionVectors.get(sectionInput.cacheKey()));
        if (fused == null) fused = new double[normalized.length];
        double adjustedWeight = profile.weights().get(section) / totalWeight;
        for (int index = 0; index < fused.length; index += 1) fused[index] += normalized[index] * adjustedWeight;
        sectionHashes.put(section, sectionInput.inputHash());
    }
    return new FusedVector(normalize(fused), sha256(JSON.valueToTree(sectionHashes).toString()), sectionHashes);
}
void printNearest(Connection connection, UUID anchorBookId, SimilarityContract contract, ProfileContract profile, RuntimeConfig runtimeConfig, int top) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(readSql("nearest-similarity-query.sql"))) {
        String profileHash = profileHash(contract, profile); String modelVersion = modelVersion(contract, profile, runtimeConfig.embeddingsModel());
        statement.setObject(1, anchorBookId); statement.setString(2, modelVersion); statement.setString(3, profileHash);
        statement.setObject(4, anchorBookId); statement.setString(5, modelVersion); statement.setString(6, profileHash); statement.setInt(7, top);
        try (ResultSet rows = statement.executeQuery()) {
            System.out.println("Nearest fused similarity matches:"); int rank = 1;
            while (rows.next()) System.out.printf("%2d. %.6f  %s — %s%n", rank++, rows.getDouble("similarity"), rows.getString("title"), text(rows.getString("authors")));
        }
    }
}
SimilarityContract loadContract() throws Exception {
    return JSON.readValue(Files.readString(PROFILE_CONTRACT), SimilarityContract.class);
}
ProfileContract activeProfile(SimilarityContract contract, String profileId) {
    String requestedProfile = hasText(profileId) ? profileId : contract.activeProfileId();
    return contract.profiles().stream().filter(profile -> profile.id().equals(requestedProfile)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown similarity profile: " + requestedProfile));
}
void validateContract(SimilarityContract contract, ProfileContract profile) {
    double weightTotal = profile.weights().values().stream().mapToDouble(Double::doubleValue).sum();
    if (Math.abs(weightTotal - 1.0d) > 0.000001d) throw new IllegalStateException("Profile weights must sum to 1.0.");
    for (String section : contract.sectionOrder()) if (!profile.weights().containsKey(section)) throw new IllegalStateException("Missing profile weight for section=" + section);
}
Connection openConnection(RuntimeConfig runtimeConfig) throws Exception {
    Properties properties = new Properties();
    if (hasText(runtimeConfig.databaseUsername())) properties.setProperty("user", runtimeConfig.databaseUsername());
    if (hasText(runtimeConfig.databasePassword())) properties.setProperty("password", runtimeConfig.databasePassword());
    return DriverManager.getConnection(runtimeConfig.jdbcUrl(), properties);
}
RuntimeConfig loadRuntimeConfig(Options options) {
    String baseUrl = normalizeBaseUrl(required("openai.base.url", "OPENAI_BASE_URL"));
    String embeddingsModel = hasText(options.model()) ? options.model() : required("openai.embeddings.model", "OPENAI_EMBEDDINGS_MODEL");
    return new RuntimeConfig(required("spring.datasource.url", "SPRING_DATASOURCE_URL"),
        firstText("spring.datasource.username", "SPRING_DATASOURCE_USERNAME"),
        firstText("spring.datasource.password", "SPRING_DATASOURCE_PASSWORD"),
        required("openai.api.key", "OPENAI_API_KEY"), baseUrl + "/embeddings", embeddingsModel);
}
Options parseOptions(String[] args) {
    Map<String, String> parsed = new LinkedHashMap<>();
    for (String arg : args) {
        if ("--dry-run".equals(arg)) parsed.put("dry-run", "true");
        else if (arg.startsWith("--") && arg.contains("=")) parsed.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
        else throw new IllegalArgumentException("Unknown option: " + arg);
    }
    return new Options(parsed.get("anchor"), parsed.get("book"), parsed.get("id-file"), parsed.get("profile"), parsed.get("model"),
        positiveInt(parsed.get("limit"), 120), positiveInt(parsed.get("top"), 20), positiveInt(parsed.get("batch-size"), 32),
        Boolean.parseBoolean(parsed.getOrDefault("dry-run", "false")));
}
List<UUID> readUuidList(PreparedStatement statement) throws Exception {
    List<UUID> ids = new ArrayList<>(); try (ResultSet rows = statement.executeQuery()) { while (rows.next()) ids.add(rows.getObject("id", UUID.class)); }
    return ids;
}
String readSql(String filename) throws Exception { return Files.readString(Path.of("src/main/resources/similarity", filename)); }
String lexicalQuery(BookProfile anchor) {
    return List.of("title", "authors", "classification_tags", "collection_categories", "ai_summary", "description").stream()
        .map(fieldName -> anchor.fieldTexts().getOrDefault(fieldName, "")).filter(fieldText -> !fieldText.isBlank())
        .findFirst().orElse(anchor.fieldTexts().getOrDefault("title", ""));
}
double[] vectorFromJson(JsonNode embeddingNode) {
    double[] vector = new double[embeddingNode.size()]; for (int index = 0; index < embeddingNode.size(); index += 1) vector[index] = embeddingNode.get(index).asDouble();
    return vector;
}
double[] parseVector(String literal) {
    String[] parts = literal.substring(1, literal.length() - 1).split(","); double[] vector = new double[parts.length];
    for (int index = 0; index < parts.length; index += 1) vector[index] = Double.parseDouble(parts[index]); return vector;
}
double[] normalize(double[] vector) {
    double sumSquares = 0.0d; for (double coordinate : vector) sumSquares += coordinate * coordinate;
    double magnitude = Math.sqrt(sumSquares); if (magnitude == 0.0d) return Arrays.copyOf(vector, vector.length);
    double[] normalized = new double[vector.length]; for (int index = 0; index < vector.length; index += 1) normalized[index] = vector[index] / magnitude;
    return normalized;
}
String vectorLiteral(double[] vector) {
    StringBuilder builder = new StringBuilder("["); for (int index = 0; index < vector.length; index += 1) { if (index > 0) builder.append(','); builder.append(Double.toString(vector[index])); }
    return builder.append(']').toString();
}
String modelVersion(SimilarityContract contract, ProfileContract profile, String embeddingsModel) {
    return embeddingsModel + ":" + profile.id() + ":" + SECTION_FUSION_REGIME;
}
String profileHash(SimilarityContract contract, ProfileContract profile) throws Exception {
    return sha256(Files.readString(PROFILE_CONTRACT));
}
String placeholders(int count) { return String.join(", ", Collections.nCopies(count, "?")); }
String sha256(String text) {
    try {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder builder = new StringBuilder();
        for (byte bite : digest) builder.append("%02x".formatted(bite)); return builder.toString();
    } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is unavailable.", failure); }
}
String firstText(String propertyName, String environmentName) {
    String systemProperty = System.getProperty(propertyName); if (hasText(systemProperty)) return systemProperty.trim();
    String environmentSetting = System.getenv(environmentName); if (hasText(environmentSetting)) return environmentSetting.trim();
    String canonicalProperty = System.getProperty(environmentName); return hasText(canonicalProperty) ? canonicalProperty.trim() : "";
}
String required(String propertyName, String environmentName) {
    String setting = firstText(propertyName, environmentName); if (!hasText(setting)) throw new IllegalStateException("Missing required setting: " + environmentName); return setting;
}
String normalizeBaseUrl(String raw) {
    String normalized = raw.trim(); while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    if (normalized.endsWith("/embeddings")) normalized = normalized.substring(0, normalized.length() - "/embeddings".length());
    return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
}
String text(String raw) { return raw == null ? "" : raw.replaceAll("\\s+", " ").trim(); }
String preview(String raw) { String normalized = text(raw); return normalized.length() <= 240 ? normalized : normalized.substring(0, 240); }
boolean hasText(String raw) { return raw != null && !raw.trim().isEmpty(); }
int positiveInt(String raw, int fallback) {
    if (!hasText(raw)) return fallback; int parsed = Integer.parseInt(raw.trim());
    if (parsed < 1) throw new IllegalArgumentException("Expected positive integer but got " + raw); return parsed;
}
record RuntimeConfig(String jdbcUrl, String databaseUsername, String databasePassword, String apiKey, String embeddingsUrl, String embeddingsModel) {}
record Options(String anchorIdentifier, String bookIdentifier, String idFilePath, String profileId, String model, int limit, int top, int batchSize, boolean dryRun) {}
record SectionCacheKey(UUID bookId, String sectionKey, String inputHash) {}
record SectionInput(UUID bookId, String sectionKey, String inputHash, String sectionText, String inputPreview) { SectionCacheKey cacheKey() { return new SectionCacheKey(bookId, sectionKey, inputHash); } }
record BookProfile(UUID bookId, Map<String, String> fieldTexts) {}
record FusedVector(double[] embedding, String sectionHash, Map<String, String> sectionInputHashes) {}
record ProfileContract(String id, String description, Map<String, Double> weights) implements java.io.Serializable {}
record SimilarityContract(String activeProfileId, List<String> sectionOrder, List<ProfileContract> profiles) {}
