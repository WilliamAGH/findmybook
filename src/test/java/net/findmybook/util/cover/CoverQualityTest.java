package net.findmybook.util.cover;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoverQualityTest {

    @Test
    void should_ReturnTier0_When_NoCoverExists() {
        assertThat(CoverQuality.rank(null, null, null, null, null, null)).isZero();
        assertThat(CoverQuality.rank("", "", null, null, null, null)).isZero();
    }

    @Test
    void should_ReturnTier1_When_CoverIsGrayscale() {
        assertThat(CoverQuality.rank("covers/abc.jpg", null, 800, 1200, true, true)).isEqualTo(1);
        assertThat(CoverQuality.rank(null, "https://example.com/cover.jpg", 128, 192, false, true)).isEqualTo(1);
    }

    @Test
    void should_ReturnTier2_When_ColorCoverBelowDisplayThreshold() {
        assertThat(CoverQuality.rank(null, "https://example.com/tiny.jpg", 100, 150, false, false)).isEqualTo(2);
    }

    @Test
    void should_ReturnTier3_When_ColorCoverMeetsDisplayThreshold() {
        assertThat(CoverQuality.rank(null, "https://example.com/medium.jpg", 300, 450, false, false)).isEqualTo(3);
    }

    @Test
    void should_ReturnTier4_When_HighResColorWithoutS3() {
        assertThat(CoverQuality.rank(null, "https://example.com/hires.jpg", 800, 1200, true, false)).isEqualTo(4);
    }

    @Test
    void should_ReturnTier5_When_S3HighResColor() {
        assertThat(CoverQuality.rank("covers/abc.jpg", null, 800, 1200, true, false)).isEqualTo(5);
    }

    @Test
    void should_TreatNullGrayscaleAsColor() {
        int withNull = CoverQuality.rank("covers/abc.jpg", null, 800, 1200, true, null);
        int withFalse = CoverQuality.rank("covers/abc.jpg", null, 800, 1200, true, false);
        assertThat(withNull).isEqualTo(withFalse);
    }

    @Test
    void should_BackwardCompatOverloadMatchNullGrayscale() {
        int fiveArg = CoverQuality.rank("covers/abc.jpg", null, 800, 1200, true);
        int sixArg = CoverQuality.rank("covers/abc.jpg", null, 800, 1200, true, null);
        assertThat(fiveArg).isEqualTo(sixArg);
    }

    @Test
    void should_RankFromUrl_GrayscaleTier1() {
        assertThat(CoverQuality.rankFromUrl("https://example.com/cover.jpg", 800, 1200, true, true)).isEqualTo(1);
    }

    @Test
    void should_RankFromUrl_BackwardCompatMatchNull() {
        int fourArg = CoverQuality.rankFromUrl("https://example.com/cover.jpg", 800, 1200, true);
        int fiveArg = CoverQuality.rankFromUrl("https://example.com/cover.jpg", 800, 1200, true, null);
        assertThat(fourArg).isEqualTo(fiveArg);
    }

    @Test
    void should_ReturnTier0_When_GoogleBooksUrlMissingEdgeCurl() {
        String titlePageUrl = "https://books.google.com/books/content?id=ABC&printsec=frontcover&img=1&zoom=1";
        assertThat(CoverQuality.rankFromUrl(titlePageUrl, 200, 300, false, false)).isZero();
    }

    @Test
    void should_ReturnPositive_When_GoogleBooksUrlHasEdgeCurl() {
        String coverUrl = "https://books.google.com/books/content?id=ABC&printsec=frontcover&img=1&zoom=1&edge=curl";
        assertThat(CoverQuality.rankFromUrl(coverUrl, 200, 300, false, false)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void should_ReturnTier0_When_AspectRatioTooSquare() {
        assertThat(CoverQuality.rankFromUrl("https://example.com/square.jpg", 300, 300, false, false)).isZero();
    }

    @Test
    void should_ReturnTier0_When_AspectRatioTooTall() {
        assertThat(CoverQuality.rankFromUrl("https://example.com/tall.jpg", 200, 500, false, false)).isZero();
    }

    @Test
    void should_ReturnTier0_When_AspectRatioLandscape() {
        assertThat(CoverQuality.rankFromUrl("https://example.com/wide.jpg", 600, 300, false, false)).isZero();
    }

    @Test
    void should_IgnoreAspectRatio_When_DimensionsUnknown() {
        assertThat(CoverQuality.rankFromUrl("https://example.com/unknown.jpg", null, null, false, false))
            .isGreaterThanOrEqualTo(2);
    }
}
