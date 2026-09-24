package com.radut.plugin.bfw;

import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The include and ignore regex filters from the settings, compiled once instead of once per file event. */
final class PathFilters {
    private static final Logger LOG = Logger.getInstance(PathFilters.class);

    static final PathFilters NONE = new PathFilters("", "");

    private final List<Pattern> include;
    private final List<Pattern> ignore;

    PathFilters(String includeLines, String ignoreLines) {
        include = compile(includeLines);
        ignore = compile(ignoreLines);
    }

    boolean hasIncludePatterns() {
        return !include.isEmpty();
    }

    String firstIncludeMatch(String relativePath) {
        return firstMatch(include, relativePath);
    }

    String firstIgnoreMatch(String relativePath) {
        return firstMatch(ignore, relativePath);
    }

    private static String firstMatch(List<Pattern> patterns, String relativePath) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(relativePath).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    private static List<Pattern> compile(String lines) {
        List<Pattern> patterns = new ArrayList<>();
        if (lines == null) {
            return patterns;
        }
        for (String line : lines.split("\n")) {
            String regex = line.trim();
            if (regex.isEmpty()) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                LOG.warn("Skipping invalid regex filter: " + regex + " (" + e.getDescription() + ")");
            }
        }
        return patterns;
    }
}
