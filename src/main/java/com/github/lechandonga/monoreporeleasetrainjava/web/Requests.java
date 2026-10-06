package com.github.lechandonga.monoreporeleasetrainjava.web;

import java.util.List;
import java.util.Map;

/**
 * REST 请求体。
 */
public final class Requests {

    private Requests() {
    }

    public record RegisterPackageRequest(
            String name,
            String currentVersion,
            List<DependencyDeclaration> dependencies) {
    }

    public record DependencyDeclaration(
            String name,
            String versionRange) {
    }

    public record ReleaseRequest(
            String batchId,
            Map<String, String> changes) {
    }

    public record FailureInjectionRequest(
            String packageName,
            int times) {
    }
}
