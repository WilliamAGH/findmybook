package net.findmybook.util.cover;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrayscaleAnalyzerTest {

    @Test
    void should_ReturnTrue_When_ImageIsUniformGray() {
        BufferedImage gray = solidImage(Color.GRAY, 100, 150);
        assertThat(GrayscaleAnalyzer.isEffectivelyGrayscale(gray)).isTrue();
    }

    @Test
    void should_ReturnTrue_When_ImageIsBlackAndWhite() {
        BufferedImage bw = checkerboard(Color.BLACK, Color.WHITE, 100, 100);
        assertThat(GrayscaleAnalyzer.isEffectivelyGrayscale(bw)).isTrue();
    }

    @Test
    void should_ReturnFalse_When_ImageIsVibrantColor() {
        BufferedImage color = solidImage(Color.RED, 100, 100);
        assertThat(GrayscaleAnalyzer.isEffectivelyGrayscale(color)).isFalse();
    }

    @Test
    void should_ReturnTrue_When_ImageColorSpaceIsTypeGray() {
        ColorSpace grayCs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
        ColorModel cm = new ComponentColorModel(
            grayCs, false, false, java.awt.Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        WritableRaster raster = Raster.createInterleavedRaster(
            DataBuffer.TYPE_BYTE, 10, 10, 1, null);
        BufferedImage grayImage = new BufferedImage(cm, raster, false, null);

        assertThat(GrayscaleAnalyzer.isEffectivelyGrayscale(grayImage)).isTrue();
    }

    @Test
    void should_ReturnFalse_When_ImageIsNull() {
        assertThat(GrayscaleAnalyzer.isEffectivelyGrayscale(null)).isFalse();
    }

    @Test
    void should_ReturnFalse_When_ImageHasMixedColorAndGray() {
        // 50% red, 50% gray — should NOT be classified as grayscale (below 95% threshold)
        BufferedImage mixed = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                mixed.setRGB(x, y, y < 50 ? Color.RED.getRGB() : Color.GRAY.getRGB());
            }
        }
        assertThat(GrayscaleAnalyzer.isEffectivelyGrayscale(mixed)).isFalse();
    }

    @Test
    void should_ReturnTrue_When_ImageIsNearlyAllGrayWithTinyColorSpeck() {
        // 99% gray, 1% red — should be classified as grayscale (above 95% threshold)
        BufferedImage nearlyGray = solidImage(Color.GRAY, 100, 100);
        nearlyGray.setRGB(0, 0, Color.RED.getRGB());
        assertThat(GrayscaleAnalyzer.isEffectivelyGrayscale(nearlyGray)).isTrue();
    }

    private static BufferedImage solidImage(Color color, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int rgb = color.getRGB();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    private static BufferedImage checkerboard(Color c1, Color c2, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int rgb1 = c1.getRGB(), rgb2 = c2.getRGB();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, ((x + y) % 2 == 0) ? rgb1 : rgb2);
            }
        }
        return img;
    }
}
