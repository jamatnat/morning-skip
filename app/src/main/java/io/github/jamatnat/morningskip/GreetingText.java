package io.github.jamatnat.morningskip;

import java.util.Locale;

final class GreetingText {
    static boolean matches(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("good morning") || t.contains("good afternoon") ||
                t.contains("good evening") || t.contains("доброе утро") ||
                t.contains("добрый день") || t.contains("добрый вечер");
    }
}
