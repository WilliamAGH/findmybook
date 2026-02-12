package net.findmybook.util.cover;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Determines whether a {@link BufferedImage} is effectively grayscale/B&W.
 *
 * <p>A cover is classified as grayscale when at least {@value #GRAY_PIXEL_FRACTION_PERCENT}%
 * of sampled pixels have HSB saturation ≤ {@value #SATURATION_THRESHOLD}. This catches
 * old PDF scans, B&W photographs, and desaturated covers while allowing books with
 * incidental gray areas (e.g., a small monochrome inset) to remain classified as color.</p>
 *
 * <p>Pixel sampling uses a stride of {@value #SAMPLE_STEP} in both dimensions, consistent
 * with the existing dominant-white check in {@code ImageProcessingService}.</p>
 */
public final class GrayscaleAnalyzer {

    /** HSB saturation at or below this value is considered "gray". */
    static final float SATURATION_THRESHOLD = 0.15f;

    /** Fraction of sampled pixels that must be gray to classify the whole image. */
    static final double GRAY_PIXEL_FRACTION = 0.95;

    /** Human-readable percentage for Javadoc references and validation messages. */
    public static final int GRAY_PIXEL_FRACTION_PERCENT = 95;

    /** Sample every Nth pixel in each dimension. */
    static final int SAMPLE_STEP = 5;

    private GrayscaleAnalyzer() {
    }

    /**
     * Returns {@code true} when the image is predominantly grayscale or B&W.
     *
     * @param image the image to analyze; {@code null} returns {@code false}
     * @return whether the image is effectively grayscale
     */
    public static boolean isEffectivelyGrayscale(BufferedImage image) {
        if (image == null) {
            return false;
        }

        // Fast path: color model already declares itself gray
        if (image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY) {
            return true;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        if (width == 0 || height == 0) {
            return false;
        }

        long grayCount = 0;
        long sampledCount = 0;
        float[] hsb = new float[3];

        // Direct pixel array access for TYPE_INT_RGB / TYPE_INT_ARGB
        boolean directAccess = (image.getType() == BufferedImage.TYPE_INT_RGB
                || image.getType() == BufferedImage.TYPE_INT_ARGB)
                && image.getRaster().getDataBuffer() instanceof DataBufferInt;

        if (directAccess) {
            int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < height; y += SAMPLE_STEP) {
                int rowOffset = y * width;
                for (int x = 0; x < width; x += SAMPLE_STEP) {
                    int rgb = pixels[rowOffset + x];
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    Color.RGBtoHSB(r, g, b, hsb);
                    if (hsb[1] <= SATURATION_THRESHOLD) {
                        grayCount++;
                    }
                    sampledCount++;
                }
            }
        } else {
            for (int y = 0; y < height; y += SAMPLE_STEP) {
                for (int x = 0; x < width; x += SAMPLE_STEP) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    Color.RGBtoHSB(r, g, b, hsb);
                    if (hsb[1] <= SATURATION_THRESHOLD) {
                        grayCount++;
                    }
                    sampledCount++;
                }
            }
        }

        if (sampledCount == 0) {
            return false;
        }

        return (double) grayCount / sampledCount >= GRAY_PIXEL_FRACTION;
    }
}
