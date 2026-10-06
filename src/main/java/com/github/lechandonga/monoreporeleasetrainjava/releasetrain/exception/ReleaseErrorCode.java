package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.exception;

/**
 * 发布列车领域错误码。各类问题在开始发布前就被拒绝，并能明确区分。
 */
public enum ReleaseErrorCode {
    DUPLICATE_PACKAGE("重复声明了同一个包"),
    DUPLICATE_DEPENDENCY("同一依赖被重复声明"),
    UNKNOWN_DEPENDENCY("依赖了不存在的包"),
    CYCLIC_DEPENDENCY("存在循环依赖"),
    INVALID_VERSION("版本号或版本区间非法"),
    PRERELEASE_FORBIDDEN("预发布版本不能作为正式版本对外发布"),
    INVALID_CHANGE("无法识别或缺失的变更级别"),
    RANGE_CONFLICT("级联后的新版本不再满足依赖方声明的版本区间，该包不可发布"),
    BATCH_CONFLICT("相同批次请求正在处理中"),
    BATCH_NOT_FOUND("批次不存在"),
    RELEASE_FAILED("发布过程中出现失败");

    private final String description;

    ReleaseErrorCode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
