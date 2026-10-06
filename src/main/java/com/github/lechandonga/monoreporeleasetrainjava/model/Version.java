package com.github.lechandonga.monoreporeleasetrainjava.model;

import java.util.Objects;

/**
 * 语义化版本：major.minor.patch，可选 -prerelease 预发布后缀与 +build 元数据。
 */
public final class Version implements Comparable<Version> {

    private final int major;
    private final int minor;
    private final int patch;
    private final String prerelease;
    private final String buildMeta;

    public Version(int major, int minor, int patch, String prerelease, String buildMeta) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("版本号不能为负数");
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease == null || prerelease.isEmpty() ? null : prerelease;
        this.buildMeta = buildMeta == null || buildMeta.isEmpty() ? null : buildMeta;
    }

    public static Version parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("版本号缺失");
        }
        String t = text.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            t = t.substring(1);
        }
        String buildMeta = null;
        int plus = t.indexOf('+');
        if (plus >= 0) {
            buildMeta = t.substring(plus + 1);
            t = t.substring(0, plus);
        }
        String prerelease = null;
        int dash = t.indexOf('-');
        if (dash >= 0) {
            prerelease = t.substring(dash + 1);
            t = t.substring(0, dash);
        }
        String[] parts = t.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("不符合 semver 的版本: " + text);
        }
        try {
            return new Version(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    prerelease, buildMeta);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不符合 semver 的版本: " + text, e);
        }
    }

    public boolean isPrerelease() {
        return prerelease != null;
    }

    /**
     * 正式版本之间忽略 build 元数据比较；预发布版本低于同号正式版本。
     */
    @Override
    public int compareTo(Version other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) return c;
        c = Integer.compare(minor, other.minor);
        if (c != 0) return c;
        c = Integer.compare(patch, other.patch);
        if (c != 0) return c;
        if (prerelease == null && other.prerelease == null) return 0;
        if (prerelease == null) return 1;
        if (other.prerelease == null) return -1;
        return comparePrerelease(prerelease, other.prerelease);
    }

    private static int comparePrerelease(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            boolean na = isNumeric(pa[i]);
            boolean nb = isNumeric(pb[i]);
            int c;
            if (na && nb) {
                c = Long.compare(Long.parseLong(pa[i]), Long.parseLong(pb[i]));
            } else if (na) {
                c = -1;
            } else if (nb) {
                c = 1;
            } else {
                c = pa[i].compareTo(pb[i]);
            }
            if (c != 0) return c;
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static boolean isNumeric(String s) {
        return s.chars().allMatch(Character::isDigit);
    }

    public Version withPrerelease(String pre) {
        return new Version(major, minor, patch, pre, null);
    }

    public int major() { return major; }
    public int minor() { return minor; }
    public int patch() { return patch; }
    public String prerelease() { return prerelease; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Version v)) return false;
        return compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, prerelease);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder()
                .append(major).append('.').append(minor).append('.').append(patch);
        if (prerelease != null) sb.append('-').append(prerelease);
        if (buildMeta != null) sb.append('+').append(buildMeta);
        return sb.toString();
    }
}
