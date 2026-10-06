package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model;

/**
 * 变更级别。PATCH &lt; MINOR &lt; MAJOR，级联时取最大值。
 */
public enum ChangeLevel {
    PATCH,
    MINOR,
    MAJOR;

    public ChangeLevel max(ChangeLevel other) {
        return other.ordinal() > ordinal() ? other : this;
    }

    public SemanticVersion apply(SemanticVersion base) {
        return switch (this) {
            case MAJOR -> base.bumpMajor();
            case MINOR -> base.bumpMinor();
            case PATCH -> base.bumpPatch();
        };
    }
}
