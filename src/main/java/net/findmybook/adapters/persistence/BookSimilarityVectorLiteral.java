package net.findmybook.adapters.persistence;

import java.util.ArrayList;
import java.util.List;

final class BookSimilarityVectorLiteral {

    private BookSimilarityVectorLiteral() {
    }

    static List<Float> parseHalfvec(String vectorText) {
        String trimmed = vectorText.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalStateException("Unexpected halfvec text format");
        }
        String body = trimmed.substring(1, trimmed.length() - 1);
        if (body.isBlank()) {
            return List.of();
        }
        String[] values = body.split(",");
        List<Float> floats = new ArrayList<>(values.length);
        for (String value : values) {
            floats.add(Float.parseFloat(value.trim()));
        }
        return List.copyOf(floats);
    }

    static String toHalfvecLiteral(List<Float> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            throw new IllegalArgumentException("embedding is required");
        }
        StringBuilder vectorBuilder = new StringBuilder(embedding.size() * 12);
        vectorBuilder.append('[');
        for (int index = 0; index < embedding.size(); index++) {
            if (index > 0) {
                vectorBuilder.append(',');
            }
            vectorBuilder.append(Float.toString(embedding.get(index)));
        }
        vectorBuilder.append(']');
        return vectorBuilder.toString();
    }
}
