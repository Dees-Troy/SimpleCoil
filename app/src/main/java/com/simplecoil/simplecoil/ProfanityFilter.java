package com.simplecoil.simplecoil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class ProfanityFilter {
    private static final Set<String> PROFANE_WORDS = new HashSet<>(Arrays.asList(
            "fuck", "fucking", "fucked", "fucker", "fuk",
            "shit", "shitting", "shitty", "shat",
            "bitch", "bitching", "bitches",
            "cunt", "cunts",
            "asshole", "asswipe", "asshat", "arsehole",
            "dick", "dickhead", "cock", "cocksucker",
            "pussy", "pussies",
            "bastard", "slut", "whore",
            "nigger", "nigga", "faggot", "fag", "retard", "chink", "spic"
    ));

    public static boolean containsProfanity(String text) {
        if (text == null || text.trim().isEmpty())
            return false;

        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", " ");
        String[] words = normalized.split("\\s+");

        for (String word : words) {
            if (PROFANE_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
