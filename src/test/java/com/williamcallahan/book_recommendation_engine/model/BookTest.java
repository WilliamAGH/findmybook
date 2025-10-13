package com.williamcallahan.book_recommendation_engine.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookTest {

    @Test
    void setS3ImagePathAssignsS3ForAmazonHost() {
        Book book = new Book();

        String s3Url = "https://s3.amazonaws.com/example-bucket/covers/book.jpg";
        book.setS3ImagePath(s3Url);

        assertThat(book.getS3ImagePath()).isEqualTo(s3Url);
        assertThat(book.getExternalImageUrl()).isNull();
    }

    @Test
    void setS3ImagePathAssignsS3ForSpacesHost() {
        Book book = new Book();

        String spacesUrl = "https://nyc3.digitaloceanspaces.com/example/covers/book.jpg";
        book.setS3ImagePath(spacesUrl);

        assertThat(book.getS3ImagePath()).isEqualTo(spacesUrl);
        assertThat(book.getExternalImageUrl()).isNull();
    }

    @Test
    void setExternalImageUrlAssignsExternalForHttpAndHttps() {
        Book book = new Book();

        String httpUrl = "http://images.example.com/cover.jpg";
        book.setExternalImageUrl(httpUrl);

        assertThat(book.getExternalImageUrl()).isEqualTo(httpUrl);
        assertThat(book.getS3ImagePath()).isNull();

        String httpsUrl = "https://images.example.com/cover.jpg";
        book.setExternalImageUrl(httpsUrl);

        assertThat(book.getExternalImageUrl()).isEqualTo(httpsUrl);
        assertThat(book.getS3ImagePath()).isNull();
    }

    @Test
    void setExternalImageUrlIgnoresSpoofedQueryParams() {
        Book book = new Book();

        String spoofed = "https://images.example.com/cover.jpg?redirect=https://s3.amazonaws.com/evil";
        book.setExternalImageUrl(spoofed);

        assertThat(book.getExternalImageUrl()).isEqualTo(spoofed);
        assertThat(book.getS3ImagePath()).isNull();
    }
}
