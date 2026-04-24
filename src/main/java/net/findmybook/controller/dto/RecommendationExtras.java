package net.findmybook.controller.dto;

/**
 * Typed recommendation annotations attached to a similar-book response.
 *
 * <p>Carries the ranking signals surfaced from recommendation sources so the
 * response boundary avoids raw extras payloads. Null fields are omitted from
 * the serialized extras map.</p>
 *
 * @param score similarity or ranking score, null when absent
 * @param reason recommendation reason label, null or blank when absent
 * @param source recommendation source label, null or blank when absent
 */
public record RecommendationExtras(Double score, String reason, String source) {

    /**
     * Returns true when no annotation fields are present.
     */
    public boolean isEmpty() {
        return score == null
            && (reason == null || reason.isBlank())
            && (source == null || source.isBlank());
    }
}
