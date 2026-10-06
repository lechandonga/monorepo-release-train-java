package com.github.lechandonga.monoreporeleasetrainjava.store;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.monoreporeleasetrainjava.model.ChangeLevel;
import com.github.lechandonga.monoreporeleasetrainjava.model.ReleaseBatch;
import com.github.lechandonga.monoreporeleasetrainjava.model.ReleaseItem;
import com.github.lechandonga.monoreporeleasetrainjava.model.Version;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发布批次存储：批次进度持久化到本地 JSON，进程重启后仍可查询与重试。
 */
@Component
public class BatchStore {

    private static final Logger log = LoggerFactory.getLogger(BatchStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path storageFile;
    private final Map<String, ReleaseBatch> batches = new LinkedHashMap<>();

    public BatchStore(@Value("${release-train.data-dir:data}") String dataDir) {
        this.storageFile = Path.of(dataDir, "batches.json");
    }

    @PostConstruct
    synchronized void load() {
        if (!Files.exists(storageFile)) {
            return;
        }
        try {
            List<BatchRecord> records = mapper.readValue(storageFile.toFile(),
                    new TypeReference<>() {});
            for (BatchRecord r : records) {
                ReleaseBatch batch = new ReleaseBatch(r.id);
                for (ItemRecord ir : r.items) {
                    ReleaseItem item = new ReleaseItem(ir.packageName,
                            ChangeLevel.valueOf(ir.changeLevel), ir.cascaded,
                            ir.fromVersion == null ? null : Version.parse(ir.fromVersion));
                    if (ir.targetVersion != null) {
                        item.targetVersion(Version.parse(ir.targetVersion));
                    }
                    item.status(ReleaseItem.Status.valueOf(ir.status));
                    if (ir.unreleasableReason != null) item.markUnreleasable(ir.unreleasableReason);
                    item.failureReason(ir.failureReason);
                    batch.items().add(item);
                }
                batch.state(ReleaseBatch.State.valueOf(r.state));
                batches.put(batch.id(), batch);
            }
            log.info("已恢复 {} 个发布批次的本地进度: {}", batches.size(), storageFile.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("加载批次进度失败: " + storageFile, e);
        }
    }

    public synchronized Optional<ReleaseBatch> find(String id) {
        return Optional.ofNullable(batches.get(id));
    }

    public synchronized ReleaseBatch get(String id) {
        return batches.get(id);
    }

    /**
     * 幂等插入：已存在同 id 批次时返回已有实例。
     */
    public synchronized ReleaseBatch putIfAbsent(ReleaseBatch batch) {
        ReleaseBatch existing = batches.putIfAbsent(batch.id(), batch);
        if (existing == null) {
            persist();
            return batch;
        }
        return existing;
    }

    public synchronized void save(ReleaseBatch batch) {
        persist();
    }

    private void persist() {
        try {
            Files.createDirectories(storageFile.getParent());
            List<BatchRecord> records = batches.values().stream().map(b -> {
                List<ItemRecord> items = b.items().stream().map(i -> new ItemRecord(
                        i.packageName(),
                        i.changeLevel().name(),
                        i.cascaded(),
                        i.fromVersion() == null ? null : i.fromVersion().toString(),
                        i.targetVersion() == null ? null : i.targetVersion().toString(),
                        i.status().name(),
                        i.unreleasableReason(),
                        i.failureReason())).toList();
                return new BatchRecord(b.id(), b.state().name(), items);
            }).toList();
            Path tmp = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), records);
            Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("持久化批次进度失败: " + storageFile, e);
        }
    }

    record ItemRecord(
            @JsonProperty("packageName") String packageName,
            @JsonProperty("changeLevel") String changeLevel,
            @JsonProperty("cascaded") boolean cascaded,
            @JsonProperty("fromVersion") String fromVersion,
            @JsonProperty("targetVersion") String targetVersion,
            @JsonProperty("status") String status,
            @JsonProperty("unreleasableReason") String unreleasableReason,
            @JsonProperty("failureReason") String failureReason) {
    }

    record BatchRecord(
            @JsonProperty("id") String id,
            @JsonProperty("state") String state,
            @JsonProperty("items") List<ItemRecord> items) {
    }
}
