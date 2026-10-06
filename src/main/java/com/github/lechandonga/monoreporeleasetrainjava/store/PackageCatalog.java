package com.github.lechandonga.monoreporeleasetrainjava.store;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.monoreporeleasetrainjava.model.PackageDef;
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
 * 包目录：保存每个包当前已发布版本与依赖声明，数据持久化在本地 JSON 文件中，
 * 不依赖任何外部服务或凭据。
 */
@Component
public class PackageCatalog {

    private static final Logger log = LoggerFactory.getLogger(PackageCatalog.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path storageFile;

    /** name -> 包定义，LinkedHashMap 保证稳定顺序。 */
    private final Map<String, PackageDef> packages = new LinkedHashMap<>();

    public PackageCatalog(@Value("${release-train.data-dir:data}") String dataDir) {
        this.storageFile = Path.of(dataDir, "catalog.json");
    }

    @PostConstruct
    synchronized void load() {
        if (!Files.exists(storageFile)) {
            log.info("本地包目录不存在，从空目录启动: {}", storageFile.toAbsolutePath());
            return;
        }
        try {
            List<PackageRecord> records = mapper.readValue(storageFile.toFile(),
                    new TypeReference<>() {});
            packages.clear();
            for (PackageRecord r : records) {
                PackageDef def = new PackageDef(r.name,
                        r.currentVersion == null ? null : Version.parse(r.currentVersion));
                if (r.dependencies != null) {
                    def.dependencies().putAll(r.dependencies);
                }
                packages.put(def.name(), def);
            }
            log.info("已加载本地包目录，共 {} 个包: {}", packages.size(), storageFile.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("加载包目录失败: " + storageFile, e);
        }
    }

    public synchronized Optional<PackageDef> find(String name) {
        return Optional.ofNullable(packages.get(name));
    }

    public synchronized PackageDef require(String name) {
        PackageDef def = packages.get(name);
        if (def == null) {
            return null;
        }
        return def;
    }

    public synchronized Map<String, PackageDef> all() {
        return Map.copyOf(packages);
    }

    /**
     * 注册或更新一个包。
     */
    public synchronized PackageDef upsert(String name, String currentVersion,
                                          Map<String, String> dependencies) {
        PackageDef def = packages.computeIfAbsent(name,
                n -> new PackageDef(n, null));
        if (currentVersion != null && !currentVersion.isBlank()) {
            def.currentVersion(Version.parse(currentVersion));
        }
        if (dependencies != null) {
            def.dependencies().clear();
            def.dependencies().putAll(dependencies);
        }
        persist();
        return def;
    }

    /**
     * 发布成功后更新当前版本。
     */
    public synchronized void updatePublishedVersion(String name, Version version) {
        PackageDef def = packages.get(name);
        if (def == null) {
            throw new IllegalArgumentException("包不存在: " + name);
        }
        def.currentVersion(version);
        persist();
    }

    private void persist() {
        try {
            Files.createDirectories(storageFile.getParent());
            List<PackageRecord> records = packages.values().stream()
                    .map(d -> new PackageRecord(d.name(),
                            d.currentVersion() == null ? null : d.currentVersion().toString(),
                            new LinkedHashMap<>(d.dependencies())))
                    .toList();
            Path tmp = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), records);
            Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("持久化包目录失败: " + storageFile, e);
        }
    }

    record PackageRecord(
            @JsonProperty("name") String name,
            @JsonProperty("currentVersion") String currentVersion,
            @JsonProperty("dependencies") Map<String, String> dependencies) {
    }
}
