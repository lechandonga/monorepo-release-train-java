package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地文件系统发布器：每个 包/版本 对应一个制品文件。
 * 写入采用临时文件 + 原子 move，发布失败不会留下半成品；
 * 已存在的同 包+版本 直接视为成功，保证重试不产生重复记录。
 * 支持一次性失败注入，用于模拟整批发布中途失败。
 */
public class LocalFilePublisher implements PackagePublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalFilePublisher.class);

    private final Path repositoryRoot;
    private final Set<String> oneShotFailures = ConcurrentHashMap.newKeySet();

    public LocalFilePublisher(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
    }

    /** 安排下一次发布 包@版本 时失败一次（重试时不再失败），仅用于测试。 */
    public void failOnce(String packageName, String version) {
        oneShotFailures.add(key(packageName, version));
    }

    @Override
    public void publish(String packageName, String version) {
        Path artifact = repositoryRoot.resolve(packageName).resolve(version + ".txt");
        if (Files.exists(artifact)) {
            log.info("[发布] 制品已存在，幂等跳过: {}@{}", packageName, version);
            return;
        }
        if (!oneShotFailures.remove(key(packageName, version))) {
            doPublish(artifact, packageName, version);
            return;
        }
        log.warn("[发布] 注入失败: {}@{}", packageName, version);
        throw new IllegalStateException("模拟发布失败: " + packageName + "@" + version);
    }

    private void doPublish(Path artifact, String packageName, String version) {
        try {
            Files.createDirectories(artifact.getParent());
            Path tmp = repositoryRoot.resolve(packageName).resolve(version + ".tmp");
            Files.writeString(tmp, "package=" + packageName + "\nversion=" + version + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, artifact, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, artifact, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("[发布] 成功: {}@{}", packageName, version);
        } catch (IOException ex) {
            throw new UncheckedIOException("发布失败: " + packageName + "@" + version, ex);
        }
    }

    private static String key(String packageName, String version) {
        return packageName + "@" + version;
    }
}
