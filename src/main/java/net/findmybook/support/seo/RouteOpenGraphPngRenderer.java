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
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders a branded 1200x630 OpenGraph PNG for non-book (route-level) social previews.
 *
 * <p>Uses the same dark gradient card aesthetic as {@link BookOpenGraphPngRenderer}
 * with the findmybook logo centered prominently instead of book-specific metadata.
 * The rendered image is deterministic and cached after the first call.
 */
@Component
public class RouteOpenGraphPngRenderer {

    private static final Logger log = LoggerFactory.getLogger(RouteOpenGraphPngRenderer.class);
    private static final int CANVAS_WIDTH = 1200;
    private static final int CANVAS_HEIGHT = 630;
    private static final int OUTER_PADDING = 34;
    private static final int LOGO_DISPLAY_WIDTH = 650;
    private static final int TAGLINE_FONT_SIZE = 30;
    private static final int DOMAIN_FONT_SIZE = 22;
    private static final String BRAND_LOGO_RESOURCE = "static/images/findmybook-logo.png";
    private static final String TAGLINE_TEXT = "Discover your next favorite read";
    private static final String DOMAIN_TEXT = "findmybook.net";
    private static final Color TAGLINE_COLOR = new Color(173, 188, 210);
    private static final Color DOMAIN_COLOR = new Color(130, 150, 180);

    private volatile BufferedImage brandLogo;
    private volatile byte[] cachedImage;

    /**
     * Returns the rendered route-level OpenGraph PNG.
     *
     * <p>The result is computed once and cached in-process; subsequent calls
     * return the same byte array without re-rendering.
     *
     * @return encoded 1200x630 PNG bytes
     */
    public byte[] render() {
        byte[] cached = cachedImage;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedImage != null) {
                return cachedImage;
            }
            BufferedImage canvas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = canvas.createGraphics();
            try {
                applyQualityHints(graphics);
                paintBackground(graphics);
                drawCenteredLogo(graphics);
                drawTagline(graphics);
                drawDomainLabel(graphics);
            } finally {
                graphics.dispose();
            }
            cachedImage = encodePng(canvas);
            return cachedImage;
        }
    }

    private void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    /**
     * Paints the dark gradient background with accent circles, scan lines, and a rounded card shell.
     * Visual parity with {@link BookOpenGraphPngRenderer#paintBackground}.
     */
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

    private void drawCenteredLogo(Graphics2D graphics) {
        BufferedImage logoImage = loadBrandLogo();
        if (logoImage == null || logoImage.getWidth() <= 0 || logoImage.getHeight() <= 0) {
            drawFallbackBrandText(graphics);
            return;
        }
        int drawWidth = LOGO_DISPLAY_WIDTH;
        int drawHeight = Math.max(1, (int) Math.round(
            (double) logoImage.getHeight() * drawWidth / logoImage.getWidth()));
        int logoX = (CANVAS_WIDTH - drawWidth) / 2;
        int logoY = (CANVAS_HEIGHT - drawHeight) / 2 - 40;
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.95f));
        graphics.drawImage(logoImage, logoX, logoY, drawWidth, drawHeight, null);
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private void drawFallbackBrandText(Graphics2D graphics) {
        graphics.setColor(new Color(230, 235, 245));
        graphics.setFont(new Font("SansSerif", Font.BOLD, 72));
        FontMetrics metrics = graphics.getFontMetrics();
        String text = "findmybook";
        int textX = (CANVAS_WIDTH - metrics.stringWidth(text)) / 2;
        int textY = CANVAS_HEIGHT / 2 - 20;
        graphics.drawString(text, textX, textY);
    }

    private void drawTagline(Graphics2D graphics) {
        graphics.setColor(TAGLINE_COLOR);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, TAGLINE_FONT_SIZE));
        FontMetrics metrics = graphics.getFontMetrics();
        int textX = (CANVAS_WIDTH - metrics.stringWidth(TAGLINE_TEXT)) / 2;
        int textY = (CANVAS_HEIGHT / 2) + 90;
        graphics.drawString(TAGLINE_TEXT, textX, textY);
    }

    private void drawDomainLabel(Graphics2D graphics) {
        graphics.setColor(DOMAIN_COLOR);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, DOMAIN_FONT_SIZE));
        FontMetrics metrics = graphics.getFontMetrics();
        int textX = (CANVAS_WIDTH - metrics.stringWidth(DOMAIN_TEXT)) / 2;
        int textY = CANVAS_HEIGHT - OUTER_PADDING - 30;
        graphics.drawString(DOMAIN_TEXT, textX, textY);
    }

    private byte[] encodePng(BufferedImage canvas) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            boolean encoded = ImageIO.write(canvas, "png", outputStream);
            if (!encoded) {
                throw new IllegalStateException("No PNG writer is available for route OpenGraph rendering");
            }
            return outputStream.toByteArray();
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to encode route OpenGraph image", ioException);
        }
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
                    log.warn("Route OpenGraph logo resource not found: {}", BRAND_LOGO_RESOURCE);
                    return null;
                }
                brandLogo = ImageIO.read(logoStream);
                if (brandLogo == null) {
                    log.warn("Route OpenGraph logo resource could not be decoded: {}", BRAND_LOGO_RESOURCE);
                }
                return brandLogo;
            } catch (IOException exception) {
                log.warn("Failed to load route OpenGraph logo resource {}", BRAND_LOGO_RESOURCE, exception);
                return null;
            }
        }
    }
}
