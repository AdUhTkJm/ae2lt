package com.moakiee.thunderbolt.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable wildcard matcher optimized for registry identifiers.
 *
 * <p>Exact identifiers and the common {@code namespace:*} form use hash lookups. Less common
 * globs are compiled once and matched without regular expressions or per-call allocations.
 * Supported wildcards are {@code *} (zero or more characters) and {@code ?} (one character).
 */
public final class FastWildcardMatcher {
    private static final FastWildcardMatcher EMPTY =
            new FastWildcardMatcher(Set.of(), Set.of(), List.of(), false);

    private final Set<String> exact;
    private final Set<String> namespaceWildcards;
    private final List<Glob> globs;
    private final boolean matchesEverything;

    private FastWildcardMatcher(
            Set<String> exact,
            Set<String> namespaceWildcards,
            List<Glob> globs,
            boolean matchesEverything) {
        this.exact = exact;
        this.namespaceWildcards = namespaceWildcards;
        this.globs = globs;
        this.matchesEverything = matchesEverything;
    }

    public static FastWildcardMatcher empty() {
        return EMPTY;
    }

    public static FastWildcardMatcher compile(Collection<? extends String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return EMPTY;
        }

        var exact = new HashSet<String>();
        var namespaces = new HashSet<String>();
        var globs = new ArrayList<Glob>();
        boolean all = false;
        for (var raw : patterns) {
            if (raw == null || !isValidPattern(raw)) {
                continue;
            }
            String pattern = normalize(raw);
            if (pattern.equals("*") || pattern.equals("*:*")) {
                all = true;
                continue;
            }
            int colon = pattern.indexOf(':');
            if (colon > 0
                    && pattern.substring(colon + 1).equals("*")
                    && !containsWildcard(pattern.substring(0, colon))) {
                namespaces.add(pattern.substring(0, colon));
            } else if (!containsWildcard(pattern)) {
                exact.add(pattern);
            } else {
                globs.add(new Glob(pattern));
            }
        }
        if (!all && exact.isEmpty() && namespaces.isEmpty() && globs.isEmpty()) {
            return EMPTY;
        }
        return new FastWildcardMatcher(
                Set.copyOf(exact), Set.copyOf(namespaces), List.copyOf(globs), all);
    }

    public boolean matches(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalized = normalize(candidate);
        if (matchesEverything || exact.contains(normalized)) {
            return true;
        }
        int colon = normalized.indexOf(':');
        if (colon > 0 && namespaceWildcards.contains(normalized.substring(0, colon))) {
            return true;
        }
        for (var glob : globs) {
            if (glob.matches(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isValidPattern(Object value) {
        if (!(value instanceof String raw)) {
            return false;
        }
        String pattern = normalize(raw);
        if (pattern.equals("*")) {
            return true;
        }
        int colon = pattern.indexOf(':');
        if (colon <= 0 || colon == pattern.length() - 1 || colon != pattern.lastIndexOf(':')) {
            return false;
        }
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == ':' || c == '*' || c == '?' || c == '.' || c == '_' || c == '-') {
                continue;
            }
            if (c == '/' && i > colon) {
                continue;
            }
            boolean lowercaseLetter = c >= 'a' && c <= 'z';
            boolean digit = c >= '0' && c <= '9';
            if (!lowercaseLetter && !digit) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsWildcard(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0;
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private record Glob(String pattern) {
        private boolean matches(String value) {
            int patternIndex = 0;
            int valueIndex = 0;
            int lastStar = -1;
            int retryValueIndex = -1;
            while (valueIndex < value.length()) {
                if (patternIndex < pattern.length()) {
                    char token = pattern.charAt(patternIndex);
                    if (token == '?' || token == value.charAt(valueIndex)) {
                        patternIndex++;
                        valueIndex++;
                        continue;
                    }
                    if (token == '*') {
                        lastStar = patternIndex++;
                        retryValueIndex = valueIndex;
                        continue;
                    }
                }
                if (lastStar < 0) {
                    return false;
                }
                patternIndex = lastStar + 1;
                valueIndex = ++retryValueIndex;
            }
            while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                patternIndex++;
            }
            return patternIndex == pattern.length();
        }
    }
}
