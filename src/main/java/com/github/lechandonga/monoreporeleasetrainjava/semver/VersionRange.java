package com.github.lechandonga.monoreporeleasetrainjava.semver;

import com.github.lechandonga.monoreporeleasetrainjava.model.Version;

import java.util.ArrayList;
import java.util.List;

/**
 * 依赖方声明的版本区间，支持以空格连接的多个比较器取交集：
 * {@code ^1.2.0}、{@code ~1.2.0}、{@code >=1.0.0}、{@code >}、{@code <=}、{@code <}、
 * {@code =1.2.3}（或精确版本 {@code 1.2.3}）、{@code *}（任意版本）。
 */
public final class VersionRange {

    private record Comparator(String op, Version version) {}

    private final String raw;
    private final List<Comparator> comparators;

    private VersionRange(String raw, List<Comparator> comparators) {
        this.raw = raw;
        this.comparators = comparators;
    }

    public static VersionRange parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("版本区间缺失");
        }
        String trimmed = expression.trim();
        List<Comparator> list = new ArrayList<>();
        if (trimmed.equals("*") || trimmed.equalsIgnoreCase("x")
                || trimmed.equalsIgnoreCase("any")) {
            return new VersionRange(trimmed, list);
        }
        for (String token : trimmed.split("\\s+")) {
            expandToken(token, list);
        }
        return new VersionRange(trimmed, list);
    }

    private static void expandToken(String token, List<Comparator> list) {
        if (token.startsWith("^")) {
            Version v = Version.parse(token.substring(1));
            Version upper = v.major() != 0
                    ? new Version(v.major() + 1, 0, 0, null, null)
                    : v.minor() != 0
                        ? new Version(0, v.minor() + 1, 0, null, null)
                        : new Version(0, 0, v.patch() + 1, null, null);
            list.add(new Comparator(">=", v));
            list.add(new Comparator("<", upper));
            return;
        }
        if (token.startsWith("~")) {
            Version v = Version.parse(token.substring(1));
            list.add(new Comparator(">=", v));
            list.add(new Comparator("<", new Version(v.major(), v.minor() + 1, 0, null, null)));
            return;
        }
        for (String op : List.of(">=", "<=", "==", "=", ">", "<")) {
            if (token.startsWith(op)) {
                String normalized = op.equals("==") ? "=" : op;
                list.add(new Comparator(normalized, Version.parse(token.substring(op.length()))));
                return;
            }
        }
        list.add(new Comparator("=", Version.parse(token)));
    }

    public boolean satisfies(Version candidate) {
        for (Comparator c : comparators) {
            int cmp = candidate.compareTo(c.version());
            boolean ok = switch (c.op()) {
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                case "=" -> cmp == 0;
                default -> throw new IllegalStateException("未知比较器: " + c.op());
            };
            if (!ok) return false;
        }
        return true;
    }

    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }

}
