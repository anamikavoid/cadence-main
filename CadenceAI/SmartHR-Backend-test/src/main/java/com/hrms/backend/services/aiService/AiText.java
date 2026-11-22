package com.hrms.backend.services.aiService;

import java.util.regex.Pattern;

/** Small text helpers shared by the AI services. */
final class AiText {
    private AiText() {}

    private static final Pattern YES = Pattern.compile(
            "^\\s*(yes|y|yeah|yep|yup|sure|ok|okay|confirm|confirmed|do it|go ahead|please do|yes please|sounds good)\\s*[.!]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NO = Pattern.compile(
            "^\\s*(no|n|nope|nah|cancel|don't|dont|do not|stop|never ?mind|no thanks|no thank you)\\s*[.!]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** True for a bare "yes"/"ok"/"confirm"... (only used while the assistant is waiting for an answer). */
    static boolean isAffirmative(String text) {
        return text != null && YES.matcher(text).matches();
    }

    static boolean isNegative(String text) {
        return text != null && NO.matcher(text).matches();
    }

    /** Trims, removes line breaks and control characters, and cuts to maxLength. Never returns null. */
    static String clean(String text, int maxLength) {
        if (text == null) return "";
        String t = text.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\p{Cntrl}", "").trim();
        return t.length() > maxLength ? t.substring(0, maxLength) : t;
    }

    static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
