package com.github.lechandonga.monoreporeleasetrainjava.service;

/**
 * 错误类别，便于调用方区分不同的拒绝原因。
 */
public enum ReleaseErrorCode {
    CYCLIC_DEPENDENCY,
    DUPLICATE_DEPENDENCY,
    UNKNOWN_DEPENDENCY,
    UNKNOWN_PACKAGE,
    INVALID_CHANGE,
    VERSION_RANGE_CONFLICT,
    PRERELEASE_FORBIDDEN,
    INVALID_VERSION_RANGE,
    BATCH_CONFLICT,
    BATCH_NOT_FOUND,
    PUBLISH_FAILED
}
