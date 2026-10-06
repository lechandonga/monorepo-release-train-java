package com.github.lechandonga.monoreporeleasetrainjava.service;

import com.github.lechandonga.monoreporeleasetrainjava.model.Version;
import com.github.lechandonga.monoreporeleasetrainjava.store.PackageCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.PUBLISH_FAILED;

/**
 * 本地文件发布器：发布记录以追加方式写入 data/release-records.log，
 * 同时更新本地包目录中的当前版本。无网络、无外部凭据。
 */
@Component
public class LocalFilePublisher implements PackagePublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalFilePublisher.class);

    private final Path recordFile;
    private final PackageCatalog catalog;
    private final FailureInjector failureInjector;

    public LocalFilePublisher(@Value("${release-train.data-dir:data}") String dataDir,
                              PackageCatalog catalog, FailureInjector failureInjector) {
        this.recordFile = Path.of(dataDir, "release-records.log");
        this.catalog = catalog;
        this.failureInjector = failureInjector;
    }

    @Override
    public synchronized boolean publish(String batchId, String packageName, Version version) {
        failureInjector.maybeBlock(packageName);
        String record = batchId + "\t" + packageName + "\t" + version;
        if (alreadyPublished(record)) {
            log.info("[发布幂等] {}@{} 已在批次 {} 中发布过，跳过重复发布", packageName, version, batchId);
            return false;
        }
        if (failureInjector.shouldFail(packageName)) {
            log.warn("[发布失败-可重试] {}@{} 发布过程中出现故障（注入）", packageName, version);
            throw new ReleaseException(PUBLISH_FAILED,
                    "包 " + packageName + "@" + version + " 发布失败，可重试");
        }
        try {
            Files.createDirectories(recordFile.getParent());
            String line = Instant.now() + "\t" + record + System.lineSeparator();
            Files.writeString(recordFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("写入发布记录失败: " + recordFile, e);
        }
        catalog.updatePublishedVersion(packageName, version);
        log.info("[发布成功] {}@{} 已发布（批次 {}）", packageName, version, batchId);
        return true;
    }

    private boolean alreadyPublished(String record) {
        if (!Files.exists(recordFile)) {
            return false;
        }
        try {
            for (String line : Files.readAllLines(recordFile, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                if (parts.length >= 4
                        && (parts[1] + "\t" + parts[2] + "\t" + parts[3]).equals(record)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("读取发布记录失败: " + recordFile, e);
        }
    }
}
