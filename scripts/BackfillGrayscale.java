///usr/bin/env java --enable-preview --source 25 "$0" "$@"; exit $?

// Backfill cover metadata for existing S3 images in book_image_links.
//
// Modes:
//   grayscale   (default) — Analyze one S3 image per book, propagate is_grayscale to siblings.
//   dimensions  — Verify actual S3 image dimensions, fix estimated values, flag bad aspect ratios,
//                 and detect grayscale in the same pass.
//
// Usage:
//   CP=$(./gradlew -q --no-configuration-cache --init-script /tmp/print-cp.gradle printCp 2>/dev/null | tr -d '\n')
//   java --enable-preview --source 25 -cp "$CP" scripts/BackfillGrayscale.java              # grayscale mode
//   java --enable-preview --source 25 -cp "$CP" scripts/BackfillGrayscale.java dimensions   # dimension mode
//   Re-run until "0 rows remaining" is printed.
//
// Environment variables (read from .env or system environment):
//   SPRING_DATASOURCE_URL (postgres:// or jdbc:postgresql:// format)
//   S3_SERVER_URL, S3_BUCKET, S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedReader;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

// Mirrors ImageDimensionUtils constants
static final double MIN_ASPECT_RATIO = 1.2;
static final double MAX_ASPECT_RATIO = 2.0;
static final int HIGH_RES_PIXEL_THRESHOLD = 320_000;
static final int BATCH_SIZE = 500;

void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "grayscale";
    if (!mode.equals("grayscale") && !mode.equals("dimensions")) {
        System.err.println("Usage: BackfillGrayscale.java [grayscale|dimensions]");
        System.exit(1);
    }

    Map<String, String> env = loadEnv();
    String jdbcUrl = toJdbcUrl(envVar(env, "SPRING_DATASOURCE_URL"));
    S3Client s3 = S3Client.builder()
        .endpointOverride(URI.create(envVar(env, "S3_SERVER_URL")))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(envVar(env, "S3_ACCESS_KEY_ID"), envVar(env, "S3_SECRET_ACCESS_KEY"))))
        .forcePathStyle(true)
        .build();
    String bucket = envVar(env, "S3_BUCKET");

    try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
        switch (mode) {
            case "grayscale"  -> runGrayscaleBackfill(conn, s3, bucket);
            case "dimensions" -> runDimensionBackfill(conn, s3, bucket);
        }
    }
}

// ── Grayscale mode ───────────────────────────────────────────────────────────

void runGrayscaleBackfill(Connection conn, S3Client s3, String bucket) throws Exception {
    String selectSql = """
        SELECT DISTINCT ON (book_id) book_id, id, s3_image_path
        FROM book_image_links
        WHERE s3_image_path IS NOT NULL AND is_grayscale IS NULL
        ORDER BY book_id,
            (image_type = 'canonical') DESC,
            COALESCE(is_high_resolution, false) DESC,
            (COALESCE(width, 0) * COALESCE(height, 0)) DESC, id
        LIMIT ?
        """;
    String updateSql = """
        UPDATE book_image_links SET is_grayscale = ?, updated_at = NOW()
        WHERE book_id = ? AND is_grayscale IS NULL
        """;

    int books = 0, links = 0, grayscaleLinks = 0, errors = 0;
    while (true) {
        var batch = new java.util.ArrayList<Object[]>();
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setInt(1, BATCH_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    batch.add(new Object[]{ rs.getString("book_id"), rs.getObject("id"), rs.getString("s3_image_path") });
                }
            }
        }
        if (batch.isEmpty()) break;

        try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
            for (Object[] row : batch) {
                String bookId = (String) row[0];
                String s3Key = (String) row[2];
                try {
                    BufferedImage img = downloadS3Image(s3, bucket, s3Key);
                    boolean grayscale = img != null && isEffectivelyGrayscale(img);
                    updatePs.setBoolean(1, grayscale);
                    updatePs.setString(2, bookId);
                    int updated = updatePs.executeUpdate();
                    books++;
                    links += updated;
                    if (grayscale) grayscaleLinks += updated;
                } catch (Exception ex) {
                    errors++;
                    updatePs.setBoolean(1, false);
                    updatePs.setString(2, bookId);
                    links += updatePs.executeUpdate();
                    books++;
                    System.err.printf("Error %s (book=%s): %s%n", s3Key, bookId, ex.getMessage());
                }
                if (books % 50 == 0) {
                    System.out.printf("[grayscale] %d books, %d links updated, %d grayscale, %d errors%n",
                        books, links, grayscaleLinks, errors);
                }
            }
        }
    }

    // Final propagation pass: if a book already has any known grayscale value
    // (true or false), apply it to linked rows that are still NULL.
    PropagationResult propagated = propagateKnownGrayscaleToNullSiblings(conn);
    links += propagated.updatedLinks();
    grayscaleLinks += propagated.updatedGrayscaleLinks();

    System.out.printf("[grayscale] Done. %d books, %d links updated, %d grayscale, %d errors%n",
        books, links, grayscaleLinks, errors);
}

record PropagationResult(int updatedLinks, int updatedGrayscaleLinks) {}

PropagationResult propagateKnownGrayscaleToNullSiblings(Connection conn) throws Exception {
    String propagationSql = """
        WITH source AS (
            SELECT DISTINCT ON (book_id)
                book_id,
                is_grayscale
            FROM book_image_links
            WHERE is_grayscale IS NOT NULL
            ORDER BY
                book_id,
                (image_type = 'canonical') DESC,
                COALESCE(is_high_resolution, false) DESC,
                (COALESCE(width, 0) * COALESCE(height, 0)) DESC,
                id
        )
        UPDATE book_image_links target
        SET is_grayscale = source.is_grayscale,
            updated_at = NOW()
        FROM source
        WHERE target.book_id = source.book_id
          AND target.is_grayscale IS NULL
        RETURNING target.is_grayscale
        """;

    int updated = 0;
    int grayscale = 0;
    try (PreparedStatement propagationPs = conn.prepareStatement(propagationSql);
         ResultSet rs = propagationPs.executeQuery()) {
        while (rs.next()) {
            updated++;
            if (rs.getBoolean("is_grayscale")) {
                grayscale++;
            }
        }
    }
    if (updated > 0) {
        System.out.printf("[grayscale] Propagated known values to %d linked rows (%d grayscale)%n", updated, grayscale);
    }
    return new PropagationResult(updated, grayscale);
}

// ── Dimensions mode ──────────────────────────────────────────────────────────

void runDimensionBackfill(Connection conn, S3Client s3, String bucket) throws Exception {
    // Target S3 rows whose stored dimensions match Google type estimates
    String selectSql = """
        SELECT id, book_id, s3_image_path, width, height
        FROM book_image_links
        WHERE s3_image_path IS NOT NULL
          AND download_error IS NULL
          AND (width, height) IN (
              (64, 96), (128, 192), (300, 450),
              (400, 600), (600, 900), (800, 1200),
              (512, 768)
          )
        ORDER BY created_at DESC
        LIMIT ?
        """;
    String updateSql = """
        UPDATE book_image_links
        SET width = ?, height = ?, is_high_resolution = ?,
            is_grayscale = ?, download_error = ?, updated_at = NOW()
        WHERE id = ?
        """;
    String propagateGrayscaleSql = """
        UPDATE book_image_links SET is_grayscale = ?, updated_at = NOW()
        WHERE book_id = ?::uuid AND is_grayscale IS NULL
        """;

    int processed = 0, corrected = 0, badAspect = 0, errors = 0;
    while (true) {
        var batch = new java.util.ArrayList<Object[]>();
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setInt(1, BATCH_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    batch.add(new Object[]{
                        rs.getString("id"),
                        rs.getString("book_id"),
                        rs.getString("s3_image_path"),
                        rs.getInt("width"),
                        rs.getInt("height")
                    });
                }
            }
        }
        if (batch.isEmpty()) break;

        try (PreparedStatement updatePs = conn.prepareStatement(updateSql);
             PreparedStatement propagatePs = conn.prepareStatement(propagateGrayscaleSql)) {
            for (Object[] row : batch) {
                String rowId = (String) row[0];
                String bookId = (String) row[1];
                String s3Key = (String) row[2];
                int storedW = (int) row[3], storedH = (int) row[4];
                try {
                    BufferedImage img = downloadS3Image(s3, bucket, s3Key);
                    if (img == null) {
                        setDownloadError(updatePs, rowId, storedW, storedH, "UnreadableImage");
                        errors++;
                        processed++;
                        System.out.printf("[dim] %s: %dx%d -> unreadable | wrote download_error=UnreadableImage%n",
                            s3Key, storedW, storedH);
                        continue;
                    }

                    int actualW = img.getWidth(), actualH = img.getHeight();
                    double ratio = actualW == 0 ? 0.0 : (double) actualH / actualW;
                    boolean grayscale = isEffectivelyGrayscale(img);
                    boolean highRes = (long) actualW * actualH >= HIGH_RES_PIXEL_THRESHOLD;
                    boolean dimChanged = actualW != storedW || actualH != storedH;
                    String downloadError = null;

                    if (!hasValidAspectRatio(actualW, actualH)) {
                        downloadError = "InvalidAspectRatio";
                        badAspect++;
                    }
                    if (dimChanged) corrected++;

                    updatePs.setInt(1, actualW);
                    updatePs.setInt(2, actualH);
                    updatePs.setBoolean(3, highRes);
                    updatePs.setBoolean(4, grayscale);
                    if (downloadError != null) {
                        updatePs.setString(5, downloadError);
                    } else {
                        updatePs.setNull(5, java.sql.Types.VARCHAR);
                    }
                    updatePs.setString(6, rowId);
                    updatePs.executeUpdate();

                    // Propagate grayscale to sibling rows for this book
                    propagatePs.setBoolean(1, grayscale);
                    propagatePs.setString(2, bookId);
                    int siblings = propagatePs.executeUpdate();

                    // Per-row log: stored -> actual -> what was written
                    String action = dimChanged
                        ? String.format("%dx%d -> %dx%d (%.2f)", storedW, storedH, actualW, actualH, ratio)
                        : String.format("%dx%d unchanged (%.2f)", actualW, actualH, ratio);
                    String flags = (grayscale ? " grayscale" : "") + (highRes ? " highRes" : "");
                    String errorFlag = downloadError != null ? " FLAGGED " + downloadError : "";
                    String siblingNote = siblings > 0 ? String.format(" +%d siblings", siblings) : "";
                    System.out.printf("[dim] %s: %s%s%s | wrote postgres OK%s%n",
                        s3Key, action, flags, errorFlag, siblingNote);
                } catch (Exception ex) {
                    errors++;
                    try {
                        setDownloadError(updatePs, rowId, storedW, storedH, "BackfillError: " + ex.getMessage());
                        System.err.printf("[dim] %s: %dx%d -> ERROR %s | wrote download_error to postgres%n",
                            s3Key, storedW, storedH, ex.getMessage());
                    } catch (Exception writeEx) {
                        System.err.printf("[dim] %s: %dx%d -> ERROR %s | FAILED to write postgres: %s%n",
                            s3Key, storedW, storedH, ex.getMessage(), writeEx.getMessage());
                    }
                }
                processed++;
                if (processed % 50 == 0) {
                    System.out.printf("[dimensions] %d processed, %d corrected, %d bad aspect, %d errors%n",
                        processed, corrected, badAspect, errors);
                }
            }
        }
    }
    System.out.printf("[dimensions] Done. %d processed, %d corrected, %d bad aspect, %d errors%n",
        processed, corrected, badAspect, errors);
}

static void setDownloadError(PreparedStatement ps, String rowId, int w, int h, String error) throws Exception {
    ps.setInt(1, w);
    ps.setInt(2, h);
    ps.setBoolean(3, false);
    ps.setBoolean(4, false);
    ps.setString(5, error);
    ps.setString(6, rowId);
    ps.executeUpdate();
}

// ── Shared image analysis ────────────────────────────────────────────────────

static BufferedImage downloadS3Image(S3Client s3, String bucket, String key) throws Exception {
    try (InputStream is = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
        return ImageIO.read(is);
    }
}

static boolean hasValidAspectRatio(int width, int height) {
    if (width <= 0 || height <= 0) return false;
    double ratio = (double) height / width;
    return ratio >= MIN_ASPECT_RATIO && ratio <= MAX_ASPECT_RATIO;
}

// Inline GrayscaleAnalyzer — duplicated from net.findmybook.util.cover.GrayscaleAnalyzer
// to keep this script self-contained.
static boolean isEffectivelyGrayscale(BufferedImage image) {
    if (image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY) {
        return true;
    }
    int w = image.getWidth(), h = image.getHeight();
    if (w == 0 || h == 0) return false;
    int step = 5;
    long gray = 0, sampled = 0;
    float[] hsb = new float[3];
    boolean direct = (image.getType() == BufferedImage.TYPE_INT_RGB
            || image.getType() == BufferedImage.TYPE_INT_ARGB)
            && image.getRaster().getDataBuffer() instanceof DataBufferInt;
    if (direct) {
        int[] px = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < h; y += step) {
            int off = y * w;
            for (int x = 0; x < w; x += step) {
                int rgb = px[off + x];
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                if (hsb[1] <= 0.15f) gray++;
                sampled++;
            }
        }
    } else {
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int rgb = image.getRGB(x, y);
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                if (hsb[1] <= 0.15f) gray++;
                sampled++;
            }
        }
    }
    return sampled > 0 && (double) gray / sampled >= 0.95;
}

// ── Environment helpers ──────────────────────────────────────────────────────

static String envVar(Map<String, String> env, String key) {
    String val = env.getOrDefault(key, System.getenv(key));
    if (val == null || val.isBlank()) {
        throw new IllegalStateException("Missing required env var: " + key);
    }
    return val;
}

static String toJdbcUrl(String url) {
    if (url.startsWith("jdbc:")) return url;
    URI uri = URI.create(url);
    String userInfo = uri.getUserInfo();
    String user = userInfo.substring(0, userInfo.indexOf(':'));
    String pass = userInfo.substring(userInfo.indexOf(':') + 1);
    String base = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
    String query = uri.getQuery();
    String sep = (query != null) ? "&" : "?";
    if (query != null) base += "?" + query;
    return base + sep + "user=" + user + "&password=" + pass;
}

static Map<String, String> loadEnv() {
    Map<String, String> env = new HashMap<>();
    Path envFile = Path.of(".env");
    if (!Files.exists(envFile)) return env;
    try (BufferedReader br = Files.newBufferedReader(envFile)) {
        String line;
        while ((line = br.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                String key = line.substring(0, eq).strip();
                String val = line.substring(eq + 1).strip();
                if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                env.put(key, val);
            }
        }
    } catch (Exception ignored) {
    }
    return env;
}
