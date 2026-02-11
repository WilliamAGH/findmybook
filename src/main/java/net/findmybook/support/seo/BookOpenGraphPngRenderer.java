package net.findmybook.support.seo;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Renders OpenGraph PNG cards for book routes.
 *
 * <p>The renderer owns only visual composition concerns (background, cover
 * placement, typography, badges, and branding) and accepts already-resolved
 * domain values from upstream orchestration services.
 */
@Component
public class BookOpenGraphPngRenderer {

    private static final Logger log = LoggerFactory.getLogger(BookOpenGraphPngRenderer.class);
    private static final int CANVAS_WIDTH = 1200;
    private static final int CANVAS_HEIGHT = 630;
    private static final int OUTER_PADDING = 34;
    private static final int CONTENT_PADDING = 42;
    private static final int COVER_WIDTH = 372;
    private static final int COVER_HEIGHT = 548;
    private static final int COVER_RADIUS = 24;
    private static final int TITLE_FONT_SIZE = 62;
    private static final int SUBTITLE_FONT_SIZE = 32;
    private static final int BRAND_FONT_SIZE = 26;
    private static final int CONTENT_GAP = 54;
    private static final int TEXT_RIGHT_BUFFER = 48;
    private static final int LOGO_TARGET_WIDTH = 322;
    private static final String DEFAULT_CARD_TITLE = "Book Details";
    private static final String BRAND_LABEL = "findmybook.net";
    private static final String BRAND_LOGO_RESOURCE = "static/images/findmybook-logo.png";
    private volatile BufferedImage brandLogo;

    /**
     * Renders a full 1200x630 PNG card from prepared metadata and cover image.
     *
     * @param title primary card title
     * @param subtitle secondary author/description line
     * @param badges visual badges displayed under subtitle
     * @param coverImage decoded cover image; when {@code null}, a placeholder is rendered
     * @return encoded PNG bytes
     */
    public byte[] render(String title, String subtitle, List<String> badges, BufferedImage coverImage) {
        BufferedImage canvas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            applyQualityHints(graphics);
            paintBackground(graphics);
            drawCover(graphics, coverImage);
            drawText(graphics, title, subtitle, badges);
            drawBrand(graphics);
        } finally {
            graphics.dispose();
        }
        return encodePng(canvas);
    }

    private byte[] encodePng(BufferedImage canvas) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            boolean encoded = ImageIO.write(canvas, "png", outputStream);
            if (!encoded) {
                throw new IllegalStateException("No PNG writer is available for OpenGraph rendering");
            }
            return outputStream.toByteArray();
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to encode OpenGraph image", ioException);
        }
    }

    private void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private void paintBackground(Graphics2D graphics) {
        graphics.setColor(new Color(8, 13, 22));
        graphics.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
        graphics.setPaint(new GradientPaint(0, 0, new Color(13, 20, 33), CANVAS_WIDTH, CANVAS_HEIGHT, new Color(20, 27, 41)));
        graphics.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.16f));
        graphics.setColor(new Color(59, 130, 246));
        graphics.fillOval(770, -130, 500, 500);
        graphics.setColor(new Color(56, 189, 248));
        graphics.fillOval(-240, 420, 460, 360);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.08f));
        graphics.setColor(new Color(255, 255, 255));
        for (int x = 0; x < CANVAS_WIDTH; x += 8) {
            graphics.drawLine(x, 0, x, CANVAS_HEIGHT);
        }
        graphics.setComposite(AlphaComposite.SrcOver);
        RoundRectangle2D shell = new RoundRectangle2D.Float(
            OUTER_PADDING,
            OUTER_PADDING,
            CANVAS_WIDTH - (OUTER_PADDING * 2f),
            CANVAS_HEIGHT - (OUTER_PADDING * 2f),
            36,
            36
        );
        graphics.setColor(new Color(13, 19, 31, 230));
        graphics.fill(shell);
        graphics.setColor(new Color(255, 255, 255, 26));
        graphics.setStroke(new BasicStroke(2f));
        graphics.draw(shell);
    }

    private void drawCover(Graphics2D graphics, BufferedImage coverImage) {
        int x = OUTER_PADDING + CONTENT_PADDING;
        int y = (CANVAS_HEIGHT - COVER_HEIGHT) / 2;
        graphics.setColor(new Color(0, 0, 0, 120));
        graphics.fillRoundRect(x - 6, y - 6, COVER_WIDTH + 12, COVER_HEIGHT + 12, COVER_RADIUS + 8, COVER_RADIUS + 8);
        graphics.setColor(new Color(0, 0, 0, 120));
        graphics.fillRoundRect(x + 14, y + 20, COVER_WIDTH, COVER_HEIGHT, COVER_RADIUS, COVER_RADIUS);
        if (coverImage == null) {
            drawCoverPlaceholder(graphics, x, y);
            return;
        }
        graphics.setClip(new RoundRectangle2D.Float(x, y, COVER_WIDTH, COVER_HEIGHT, COVER_RADIUS, COVER_RADIUS));
        double scale = Math.max((double) COVER_WIDTH / coverImage.getWidth(), (double) COVER_HEIGHT / coverImage.getHeight());
        int drawWidth = (int) Math.round(coverImage.getWidth() * scale);
        int drawHeight = (int) Math.round(coverImage.getHeight() * scale);
        graphics.drawImage(
            coverImage,
            x + (COVER_WIDTH - drawWidth) / 2,
            y + (COVER_HEIGHT - drawHeight) / 2,
            drawWidth,
            drawHeight,
            null
        );
        graphics.setClip(null);
        graphics.setColor(new Color(255, 255, 255, 26));
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawRoundRect(x, y, COVER_WIDTH, COVER_HEIGHT, COVER_RADIUS, COVER_RADIUS);
    }

    private void drawCoverPlaceholder(Graphics2D graphics, int x, int y) {
        graphics.setColor(new Color(24, 33, 52));
        graphics.fillRoundRect(x, y, COVER_WIDTH, COVER_HEIGHT, COVER_RADIUS, COVER_RADIUS);
        graphics.setColor(new Color(99, 118, 148));
        graphics.setStroke(new BasicStroke(4f));
        int placeholderX = x + ((COVER_WIDTH - 150) / 2);
        int placeholderY = y + ((COVER_HEIGHT - 200) / 2);
        graphics.drawRoundRect(placeholderX, placeholderY, 150, 200, 10, 10);
        graphics.drawLine(placeholderX + 75, placeholderY + 16, placeholderX + 75, placeholderY + 184);
        graphics.setColor(new Color(177, 191, 214));
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 24));
        graphics.drawString("No cover image", x + 98, y + COVER_HEIGHT - 68);
    }

    private void drawText(Graphics2D graphics, String title, String subtitle, List<String> badges) {
        int coverX = OUTER_PADDING + CONTENT_PADDING;
        int textX = coverX + COVER_WIDTH + CONTENT_GAP;
        int maxTextWidth = Math.max(
            200,
            CANVAS_WIDTH - OUTER_PADDING - CONTENT_PADDING - textX - TEXT_RIGHT_BUFFER
        );
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font("SansSerif", Font.BOLD, TITLE_FONT_SIZE));
        FontMetrics titleMetrics = graphics.getFontMetrics();
        List<String> titleLines = wrapTitle(titleMetrics, title, maxTextWidth, 2);
        int y = OUTER_PADDING + CONTENT_PADDING + 94;
        for (String line : titleLines) {
            graphics.drawString(line, textX, y);
            y += titleMetrics.getHeight() + 8;
        }
        graphics.setColor(new Color(173, 188, 210));
        graphics.setFont(new Font("SansSerif", Font.PLAIN, SUBTITLE_FONT_SIZE));
        FontMetrics subtitleMetrics = graphics.getFontMetrics();
        List<String> subtitleLines = wrapText(subtitleMetrics, subtitle, maxTextWidth, 2, "");
        int subtitleY = y + 30;
        for (String line : subtitleLines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            graphics.drawString(line, textX, subtitleY);
            subtitleY += subtitleMetrics.getHeight() + 4;
        }
    }

    private void drawBrand(Graphics2D graphics) {
        BufferedImage logoImage = loadBrandLogo();
        int rightInset = OUTER_PADDING + CONTENT_PADDING;
        int bottomInset = OUTER_PADDING + CONTENT_PADDING;
        if (logoImage != null && logoImage.getWidth() > 0 && logoImage.getHeight() > 0) {
            int drawWidth = LOGO_TARGET_WIDTH;
            int drawHeight = Math.max(1, (int) Math.round((double) logoImage.getHeight() * drawWidth / logoImage.getWidth()));
            int x = CANVAS_WIDTH - rightInset - drawWidth;
            int y = CANVAS_HEIGHT - bottomInset - drawHeight;
            graphics.setComposite(AlphaComposite.SrcOver.derive(0.92f));
            graphics.drawImage(logoImage, x, y, drawWidth, drawHeight, null);
            graphics.setComposite(AlphaComposite.SrcOver);
            return;
        }
        graphics.setColor(new Color(203, 213, 225));
        graphics.setFont(new Font("SansSerif", Font.PLAIN, BRAND_FONT_SIZE));
        int y = CANVAS_HEIGHT - bottomInset;
        int textWidth = graphics.getFontMetrics().stringWidth(BRAND_LABEL);
        int x = CANVAS_WIDTH - rightInset - textWidth;
        graphics.drawString(BRAND_LABEL, x, y);
    }

    private List<String> wrapTitle(FontMetrics metrics, String title, int maxWidth, int maxLines) {
        return wrapText(metrics, title, maxWidth, maxLines, DEFAULT_CARD_TITLE);
    }

    private List<String> wrapText(FontMetrics metrics,
                                  String text,
                                  int maxWidth,
                                  int maxLines,
                                  String fallbackValue) {
        int safeMaxWidth = Math.max(120, maxWidth);
        int safeMaxLines = Math.max(1, maxLines);
        String normalizedValue = StringUtils.hasText(text) ? text.trim() : fallbackValue;
        if (!StringUtils.hasText(normalizedValue)) {
            return List.of("");
        }
        String[] words = normalizedValue.split("\\s+");
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        int index = 0;
        for (; index < words.length; index++) {
            String word = words[index];
            String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
            if (metrics.stringWidth(candidate) <= safeMaxWidth) {
                currentLine.setLength(0);
                currentLine.append(candidate);
                continue;
            }
            if (currentLine.isEmpty()) {
                lines.add(trimToWidth(metrics, word, safeMaxWidth));
                if (lines.size() >= safeMaxLines) {
                    index++;
                    break;
                }
                continue;
            }
            lines.add(currentLine.toString());
            currentLine.setLength(0);
            currentLine.append(word);
            if (lines.size() >= safeMaxLines) {
                break;
            }
        }
        if (!currentLine.isEmpty() && lines.size() < safeMaxLines) {
            lines.add(currentLine.toString());
        }
        if (lines.isEmpty()) {
            lines.add(trimToWidth(metrics, normalizedValue, safeMaxWidth));
        }
        if (lines.size() > safeMaxLines) {
            lines = lines.subList(0, safeMaxLines);
        }
        boolean hasOverflow = index < words.length;
        int lastIndex = lines.size() - 1;
        String normalizedLastLine = trimToWidth(metrics, lines.get(lastIndex), safeMaxWidth);
        lines.set(
            lastIndex,
            hasOverflow ? forceEllipsis(metrics, normalizedLastLine, safeMaxWidth) : normalizedLastLine
        );
        return lines;
    }

    private String forceEllipsis(FontMetrics metrics, String value, int maxWidth) {
        String base = StringUtils.hasText(value) ? value.trim() : "";
        String ellipsis = "...";
        if (base.endsWith(ellipsis)) {
            return trimToWidth(metrics, base, maxWidth);
        }
        while (base.length() > 1 && metrics.stringWidth(base + ellipsis) > maxWidth) {
            base = base.substring(0, base.length() - 1);
        }
        if (!StringUtils.hasText(base)) {
            return ellipsis;
        }
        return base + ellipsis;
    }

    private String trimToWidth(FontMetrics metrics, String value, int maxWidth) {
        String trimmed = StringUtils.hasText(value) ? value.trim() : "";
        if (!StringUtils.hasText(trimmed)) {
            return "";
        }
        if (metrics.stringWidth(trimmed) <= maxWidth) {
            return trimmed;
        }
        String ellipsis = "...";
        while (trimmed.length() > 1 && metrics.stringWidth(trimmed + ellipsis) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return StringUtils.hasText(trimmed) ? trimmed + ellipsis : ellipsis;
    }

    private BufferedImage loadBrandLogo() {
        BufferedImage cachedLogo = brandLogo;
        if (cachedLogo != null) {
            return cachedLogo;
        }
        synchronized (this) {
            if (brandLogo != null) {
                return brandLogo;
            }
            try (InputStream logoStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(BRAND_LOGO_RESOURCE)) {
                if (logoStream == null) {
                    log.warn("OpenGraph logo resource not found: {}", BRAND_LOGO_RESOURCE);
                    return null;
                }
                brandLogo = ImageIO.read(logoStream);
                if (brandLogo == null) {
                    log.warn("OpenGraph logo resource could not be decoded: {}", BRAND_LOGO_RESOURCE);
                }
                return brandLogo;
            } catch (IOException exception) {
                log.warn("Failed to load OpenGraph logo resource {}", BRAND_LOGO_RESOURCE, exception);
                return null;
            }
        }
    }
}
