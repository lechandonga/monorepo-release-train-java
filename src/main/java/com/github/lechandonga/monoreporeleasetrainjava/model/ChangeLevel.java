package com.github.lechandonga.monoreporeleasetrainjava.model;

/**
 * 变更级别，级别从低到高为 PATCH &lt; MINOR &lt; MAJOR。
 */
public enum ChangeLevel {
    PATCH(0),
    MINOR(1),
    MAJOR(2);

    private final int rank;

    ChangeLevel(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /**
     * 与字面量解析不同，无法识别的输入直接抛出异常，而不是静默按修复处理。
     */
    public static ChangeLevel fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("change level 缺失");
        }
        return switch (raw.trim().toLowerCase()) {
            case "major", "breaking", "破坏性改动" -> MAJOR;
            case "minor", "feature", "新功能" -> MINOR;
            case "patch", "fix", "修复" -> PATCH;
            default -> throw new IllegalArgumentException("无法识别的 change level: " + raw);
        };
    }
}
