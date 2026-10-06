package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 整批发布请求：一批包的当前定义 + 每个包的变更信息。
 */
public class ReleaseRequest {

    public static class Change {
        private String packageName;
        /** MAJOR / MINOR / PATCH，大小写不敏感；无法识别或缺失都会被拒绝。 */
        private String level;

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }
    }

    private String batchId;
    private List<PackagePayload> packages = new ArrayList<>();
    private List<Change> changes = new ArrayList<>();

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public List<PackagePayload> getPackages() {
        return packages;
    }

    public void setPackages(List<PackagePayload> packages) {
        this.packages = packages == null ? new ArrayList<>() : packages;
    }

    public List<Change> getChanges() {
        return changes;
    }

    public void setChanges(List<Change> changes) {
        this.changes = changes == null ? new ArrayList<>() : changes;
    }
}
