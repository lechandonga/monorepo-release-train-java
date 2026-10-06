package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.ReleaseBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * 批次计划的本地持久化（JSON 原子写）。保证重启/重试时看到的是同一份固化计划。
 */
public class BatchStore {

    private static final Logger log = LoggerFactory.getLogger(BatchStore.class);

    private final Path batchesDir;
    private final ObjectMapper objectMapper;

    public BatchStore(Path dataDir, ObjectMapper objectMapper) {
        this.batchesDir = dataDir.resolve("batches");
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(batchesDir);
        } catch (IOException ex) {
            throw new UncheckedIOException("无法初始化批次存储目录: " + batchesDir, ex);
        }
    }

    public Optional<ReleaseBatch> find(String batchId) {
        Path file = batchFile(batchId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), ReleaseBatch.class));
        } catch (IOException ex) {
            throw new UncheckedIOException("读取批次失败: " + batchId, ex);
        }
    }

    /** 原子保存；tmp 先落同目录，再 move，杜绝半成品批次文件。 */
    public void save(ReleaseBatch batch) {
        Path file = batchFile(batch.getBatchId());
        Path tmp = batchesDir.resolve(batch.getBatchId() + ".json.tmp");
        try {
            Files.writeString(tmp, objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(batch), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("[存储] 批次 {} 已持久化，状态 {}", batch.getBatchId(), batch.getStatus());
        } catch (IOException ex) {
            throw new UncheckedIOException("保存批次失败: " + batch.getBatchId(), ex);
        }
    }

    private Path batchFile(String batchId) {
        if (!batchId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("非法批次ID: " + batchId);
        }
        return batchesDir.resolve(batchId + ".json");
    }
}
