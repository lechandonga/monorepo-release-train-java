package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model;

/**
 * 单个包在某批次中的发布计划与执行状态。POJO 便于本地 JSON 持久化。
 */
public class PackagePlan {

    public enum State {
        PENDING,
        PUBLISHED,
        FAILED,
        BLOCKED
    }

    private String packageName;
    private String fromVersion;
    private String toVersion;
    private String level;
    private String state;
    private String reason;
    private int attempts;

    public PackagePlan() {
    }

    public PackagePlan(String packageName, String fromVersion, String toVersion,
                       ChangeLevel level, State state) {
        this.packageName = packageName;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.level = level == null ? null : level.name();
        this.state = state.name();
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getFromVersion() {
        return fromVersion;
    }

    public void setFromVersion(String fromVersion) {
        this.fromVersion = fromVersion;
    }

    public String getToVersion() {
        return toVersion;
    }

    public void setToVersion(String toVersion) {
        this.toVersion = toVersion;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }
}
