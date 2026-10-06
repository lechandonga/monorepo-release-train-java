package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model;

import java.util.Objects;

/**
 * 语义化版本：MAJOR.MINOR.PATCH[-prerelease]。
 * 带预发布后缀的版本优先级低于同核心段的正式版本。
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {

    private final int major;
    private final int minor;
    private final int patch;
    private final String prerelease;

    private SemanticVersion(int major, int minor, int patch, String prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    static SemanticVersion release(int major, int minor, int patch) {
        return new SemanticVersion(major, minor, patch, null);
    }

    public static SemanticVersion parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("版本号缺失");
        }
        String text = raw.trim().startsWith("v") ? raw.trim().substring(1) : raw.trim();
        String pre = null;
        int dash = text.indexOf('-');
        if (dash >= 0) {
            pre = text.substring(dash + 1);
            text = text.substring(0, dash);
            if (pre.isBlank()) {
                throw new IllegalArgumentException("非法版本号: " + raw);
            }
        }
        String[] parts = text.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("非法版本号(应为 MAJOR.MINOR.PATCH): " + raw);
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            if (major < 0 || minor < 0 || patch < 0) {
                throw new IllegalArgumentException("非法版本号(段不能为负): " + raw);
            }
            return new SemanticVersion(major, minor, patch, pre);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("非法版本号: " + raw, ex);
        }
    }

    public boolean isPrerelease() {
        return prerelease != null;
    }

    public SemanticVersion bumpMajor() {
        return new SemanticVersion(major + 1, 0, 0, null);
    }

    public SemanticVersion bumpMinor() {
        return new SemanticVersion(major, minor + 1, 0, null);
    }

    public SemanticVersion bumpPatch() {
        return new SemanticVersion(major, minor, patch + 1, null);
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    @Override
    public int compareTo(SemanticVersion other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        if (patch != other.patch) {
            return Integer.compare(patch, other.patch);
        }
        if (prerelease == null && other.prerelease == null) {
            return 0;
        }
        if (prerelease == null) {
            return 1;
        }
        if (other.prerelease == null) {
            return -1;
        }
        return prerelease.compareTo(other.prerelease);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SemanticVersion other)) {
            return false;
        }
        return compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, prerelease);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (prerelease == null ? "" : "-" + prerelease);
    }
}
