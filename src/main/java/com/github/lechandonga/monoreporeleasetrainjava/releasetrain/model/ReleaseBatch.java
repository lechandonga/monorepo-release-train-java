package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.PackageDefinition;

/**
 * 一次整批发布请求（发布列车的一节车厢）。
 * 发布计划在首次提交时固化，后续幂等重放与重试都复用同一计划，保证结果确定。
 */
public class ReleaseBatch {

    public enum Status {
        PLANNING,
        IN_PROGRESS,
        COMPLETED,
        PARTIALLY_FAILED
    }

    private String batchId;
    private String status;
    /** 首次提交时的包定义快照，重试据此重建依赖图，保证执行顺序确定。 */
    private List<PackageDefinition> packages = new ArrayList<>();
    private List<PackagePlan> plans = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<PackagePlan> getPlans() {
        return plans;
    }

    public List<PackageDefinition> getPackages() {
        return packages;
    }

    public void setPackages(List<PackageDefinition> packages) {
        this.packages = packages == null ? new ArrayList<>() : packages;
    }

    public void setPlans(List<PackagePlan> plans) {
        this.plans = plans == null ? new ArrayList<>() : plans;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
