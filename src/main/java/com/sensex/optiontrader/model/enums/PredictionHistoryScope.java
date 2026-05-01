package com.sensex.optiontrader.model.enums;

/**
 * Controls whether prediction history lists every stored prediction or only those for the caller.
 * <ul>
 *   <li>{@link #ALL} — platform-wide history (enforced for admins only at the API).</li>
 *   <li>{@link #MINE} — only rows for the authenticated user.</li>
 * </ul>
 */
public enum PredictionHistoryScope {
    ALL,
    MINE;

    public static PredictionHistoryScope fromQueryParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        return switch (raw.trim().toLowerCase()) {
            case "mine", "user", "me" -> MINE;
            default -> ALL;
        };
    }
}
