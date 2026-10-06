package com.github.lechandonga.monoreporeleasetrainjava.model;

/**
 * 发布计划中单个包的条目。
 */
public final class ReleaseItem {
    public enum Status {
        PLANNED,
        RELEASED,
        FAILED,
        SKIPPED
    }

    private final String packageName;
    private ChangeLevel changeLevel;
    private final boolean cascaded;
    private final Version fromVersion;
    private Version targetVersion;
    private Status status = Status.PLANNED;
    private String unreleasableReason;
    private String failureReason;

    public ReleaseItem(String packageName, ChangeLevel changeLevel, boolean cascaded,
                       Version fromVersion) {
        this.packageName = packageName;
        this.changeLevel = changeLevel;
        this.cascaded = cascaded;
        this.fromVersion = fromVersion;
    }

    public String packageName() { return packageName; }
    public ChangeLevel changeLevel() { return changeLevel; }
    public void upgradeLevel(ChangeLevel level) {
        if (level.rank() > this.changeLevel.rank()) {
            this.changeLevel = level;
        }
    }
    public boolean cascaded() { return cascaded; }
    public Version fromVersion() { return fromVersion; }
    public Version targetVersion() { return targetVersion; }
    public void targetVersion(Version v) { this.targetVersion = v; }
    public Status status() { return status; }
    public void status(Status s) { this.status = s; }
    public String unreleasableReason() { return unreleasableReason; }
    public void markUnreleasable(String reason) {
        this.unreleasableReason = reason;
        this.status = Status.SKIPPED;
    }
    public String failureReason() { return failureReason; }
    public void failureReason(String reason) { this.failureReason = reason; }
}
