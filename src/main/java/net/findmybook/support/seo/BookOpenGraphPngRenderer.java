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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
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

    private static final int CANVAS_WIDTH = 1200;
    private static final int CANVAS_HEIGHT = 630;
    private static final int CANVAS_PADDING = 56;
    private static final int COVER_WIDTH = 320;
    private static final int COVER_HEIGHT = 420;
    private static final int COVER_RADIUS = 18;
    private static final int TITLE_FONT_SIZE = 56;
    private static final int SUBTITLE_FONT_SIZE = 30;
    private static final int BADGE_FONT_SIZE = 22;
    private static final int BRAND_FONT_SIZE = 28;
    private static final int CONTENT_GAP = 48;
    private static final String DEFAULT_CARD_TITLE = "Book Details";
    private static final String BRAND_LABEL = "findmybook.net";

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
        graphics.setPaint(new GradientPaint(0, 0, new Color(10, 15, 26), CANVAS_WIDTH, CANVAS_HEIGHT, new Color(26, 31, 58)));
        graphics.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

        graphics.setComposite(AlphaComposite.SrcOver.derive(0.24f));
        graphics.setColor(new Color(59, 130, 246));
        graphics.fillOval(760, -120, 520, 520);
        graphics.setColor(new Color(16, 185, 129));
        graphics.fillOval(-180, 330, 520, 420);
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private void drawCover(Graphics2D graphics, BufferedImage coverImage) {
        int x = CANVAS_PADDING;
        int y = CANVAS_PADDING;
        graphics.setColor(new Color(0, 0, 0, 120));
        graphics.fillRoundRect(x + 10, y + 18, COVER_WIDTH, COVER_HEIGHT, COVER_RADIUS, COVER_RADIUS);

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
        graphics.setColor(new Color(35, 41, 74));
        graphics.fillRoundRect(x, y, COVER_WIDTH, COVER_HEIGHT, COVER_RADIUS, COVER_RADIUS);
        graphics.setColor(new Color(148, 163, 184));
        graphics.setStroke(new BasicStroke(4f));
        graphics.drawRoundRect(x + 96, y + 118, 128, 168, 10, 10);
        graphics.drawLine(x + 160, y + 132, x + 160, y + 272);
    }

    private void drawText(Graphics2D graphics, String title, String subtitle, List<String> badges) {
        int textX = CANVAS_PADDING + COVER_WIDTH + CONTENT_GAP;
        int maxTextWidth = CANVAS_WIDTH - textX - CANVAS_PADDING;

        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font("SansSerif", Font.BOLD, TITLE_FONT_SIZE));
        FontMetrics titleMetrics = graphics.getFontMetrics();
        List<String> titleLines = wrapTitle(titleMetrics, title, maxTextWidth, 2);
        int y = CANVAS_PADDING + 76;
        for (String line : titleLines) {
            graphics.drawString(line, textX, y);
            y += titleMetrics.getHeight() + 8;
        }

        graphics.setColor(new Color(148, 163, 184));
        graphics.setFont(new Font("SansSerif", Font.PLAIN, SUBTITLE_FONT_SIZE));
        graphics.drawString(truncate(subtitle, 80), textX, y + 56);

        graphics.setFont(new Font("SansSerif", Font.BOLD, BADGE_FONT_SIZE));
        FontMetrics badgeMetrics = graphics.getFontMetrics();
        int badgeX = textX;
        int badgeY = y + 84;
        for (String badge : badges) {
            String normalizedBadge = truncate(badge.toUpperCase(Locale.ROOT), 18);
            int badgeWidth = badgeMetrics.stringWidth(normalizedBadge) + 44;
            if (badgeX + badgeWidth > textX + maxTextWidth) {
                break;
            }
            graphics.setColor(resolveBadgeColor(normalizedBadge));
            graphics.fillRoundRect(badgeX, badgeY, badgeWidth, 44, 999, 999);
            graphics.setColor(Color.WHITE);
            graphics.drawString(normalizedBadge, badgeX + 22, badgeY + 30);
            badgeX += badgeWidth + 12;
        }
    }

    private void drawBrand(Graphics2D graphics) {
        graphics.setColor(new Color(203, 213, 225));
        graphics.setFont(new Font("SansSerif", Font.PLAIN, BRAND_FONT_SIZE));
        int y = CANVAS_HEIGHT - CANVAS_PADDING;
        int textWidth = graphics.getFontMetrics().stringWidth(BRAND_LABEL);
        int x = CANVAS_WIDTH - CANVAS_PADDING - textWidth;
        graphics.drawString(BRAND_LABEL, x, y);
    }

    private List<String> wrapTitle(FontMetrics metrics, String title, int maxWidth, int maxLines) {
        String normalizedTitle = StringUtils.hasText(title) ? title.trim() : DEFAULT_CARD_TITLE;
        String[] words = normalizedTitle.split("\\s+");
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        for (String word : words) {
            String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
            if (metrics.stringWidth(candidate) <= maxWidth) {
                currentLine.setLength(0);
                currentLine.append(candidate);
                continue;
            }
            boolean lineWasEmpty = currentLine.isEmpty();
            lines.add(lineWasEmpty ? word : currentLine.toString());
            currentLine.setLength(0);
            if (!lineWasEmpty) {
                currentLine.append(word);
            }
            if (lines.size() >= maxLines) {
                break;
            }
        }
        if (!currentLine.isEmpty() && lines.size() < maxLines) {
            lines.add(currentLine.toString());
        }
        if (lines.isEmpty()) {
            lines.add(DEFAULT_CARD_TITLE);
        }
        if (lines.size() > maxLines) {
            lines = lines.subList(0, maxLines);
        }
        int lastIndex = lines.size() - 1;
        lines.set(lastIndex, trimToWidth(metrics, lines.get(lastIndex), maxWidth));
        return lines;
    }

    private String trimToWidth(FontMetrics metrics, String value, int maxWidth) {
        String trimmed = truncate(value, 96);
        if (metrics.stringWidth(trimmed) <= maxWidth) {
            return trimmed;
        }
        String ellipsis = "...";
        while (trimmed.length() > 1 && metrics.stringWidth(trimmed + ellipsis) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ellipsis;
    }

    private Color resolveBadgeColor(String badge) {
        if ("PDF".equals(badge)) {
            return new Color(139, 92, 246);
        }
        if ("EPUB".equals(badge)) {
            return new Color(16, 185, 129);
        }
        return new Color(245, 158, 11);
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(1, maxLength - 3)) + "...";
    }
}
