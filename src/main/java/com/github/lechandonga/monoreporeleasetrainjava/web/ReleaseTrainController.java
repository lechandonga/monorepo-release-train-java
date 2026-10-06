package com.github.lechandonga.monoreporeleasetrainjava.web;

import com.github.lechandonga.monoreporeleasetrainjava.model.PackageDef;
import com.github.lechandonga.monoreporeleasetrainjava.service.DependencyGraph;
import com.github.lechandonga.monoreporeleasetrainjava.service.FailureInjector;
import com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseException;
import com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseTrainService;
import com.github.lechandonga.monoreporeleasetrainjava.store.PackageCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地发布列车 REST 接口，全部操作只读写本地文件。
 */
@RestController
@RequestMapping("/api")
public class ReleaseTrainController {

    private final PackageCatalog catalog;
    private final ReleaseTrainService service;
    private final FailureInjector failureInjector;

    public ReleaseTrainController(PackageCatalog catalog, ReleaseTrainService service,
                                  FailureInjector failureInjector) {
        this.catalog = catalog;
        this.service = service;
        this.failureInjector = failureInjector;
    }

    /**
     * 注册/更新一个包；重复声明、依赖不存在、循环依赖在写入前被拒绝并可区分错误码。
     */
    @PostMapping("/packages")
    public ResponseEntity<?> registerPackage(@RequestBody Requests.RegisterPackageRequest request) {
        Map<String, PackageDef> existing = catalog.all();
        Map<String, PackageDef> merged = new LinkedHashMap<>(existing);
        PackageDef candidate = new PackageDef(request.name(),
                request.currentVersion() == null || request.currentVersion().isBlank()
                        ? null
                        : com.github.lechandonga.monoreporeleasetrainjava.model.Version
                                .parse(request.currentVersion()));
        Map<String, String> dependencyMap = new LinkedHashMap<>();
        if (request.dependencies() != null) {
            for (Requests.DependencyDeclaration dep : request.dependencies()) {
                dependencyMap.put(dep.name(), dep.versionRange());
            }
        }
        candidate.dependencies().putAll(dependencyMap);
        merged.put(request.name(), candidate);

        Map<String, List<String>> rawDeps = new LinkedHashMap<>();
        rawDeps.put(request.name(), request.dependencies() == null
                ? new ArrayList<>()
                : request.dependencies().stream()
                        .map(Requests.DependencyDeclaration::name).toList());
        for (PackageDef def : existing.values()) {
            if (!def.name().equals(request.name())) {
                rawDeps.put(def.name(), new ArrayList<>(def.dependencies().keySet()));
            }
        }
        // 发布前校验：重复声明 / 依赖不存在 / 循环依赖
        DependencyGraph.validate(rawDeps, merged.keySet());

        PackageDef saved = catalog.upsert(request.name(), request.currentVersion(),
                dependencyMap);
        return ResponseEntity.ok(packageView(saved));
    }

    @GetMapping("/packages")
    public List<Map<String, Object>> listPackages() {
        return catalog.all().values().stream().map(this::packageView).toList();
    }

    private Map<String, Object> packageView(PackageDef def) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", def.name());
        view.put("currentVersion",
                def.currentVersion() == null ? null : def.currentVersion().toString());
        view.put("dependencies", def.dependencies());
        return view;
    }

    /**
     * 提交整批发布。重复 batchId 为幂等提交；批次执行冲突时返回 409。
     */
    @PostMapping("/releases")
    public Responses.BatchView submit(@RequestBody Requests.ReleaseRequest request) {
        if (request.batchId() == null || request.batchId().isBlank()) {
            throw new ReleaseException(
                    com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.INVALID_CHANGE,
                    "batchId 不能为空");
        }
        return Responses.BatchView.from(service.submit(request.batchId(), request.changes()));
    }

    @GetMapping("/releases/{batchId}")
    public Responses.BatchView status(@PathVariable String batchId) {
        return Responses.BatchView.from(service.status(batchId));
    }

    /**
     * 重试失败/未完成的批次；批次正被推进时明确拒绝（409），不允许并发重复推进。
     */
    @PostMapping("/releases/{batchId}/retry")
    public Responses.BatchView retry(@PathVariable String batchId) {
        return Responses.BatchView.from(service.advance(batchId, true));
    }

    /** 测试辅助：让某包接下来的 times 次发布尝试失败。 */
    @PostMapping("/test/failures")
    public Map<String, String> injectFailure(@RequestBody Requests.FailureInjectionRequest request) {
        failureInjector.failNextAttempts(request.packageName(), request.times());
        return Map.of("result", "已注入 " + request.times() + " 次发布故障: " + request.packageName());
    }

    /** 测试辅助：让某包的发布阻塞住，用于确定性地制造并发窗口。 */
    @PostMapping("/test/blocks/{packageName}")
    public Map<String, String> installBlock(@PathVariable String packageName) {
        failureInjector.installBlock(packageName);
        return Map.of("result", "已安装发布阻塞点: " + packageName);
    }

    /** 测试辅助：释放阻塞点。 */
    @PostMapping("/test/blocks/{packageName}/release")
    public Map<String, String> releaseBlock(@PathVariable String packageName) {
        failureInjector.releaseBlock();
        failureInjector.awaitBlockedEntered();
        failureInjector.clearBlock();
        return Map.of("result", "已释放发布阻塞点: " + packageName);
    }

    @ExceptionHandler(ReleaseException.class)
    public ResponseEntity<Responses.ErrorView> handleReleaseException(ReleaseException e) {
        HttpStatus status = switch (e.code()) {
            case BATCH_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BATCH_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new Responses.ErrorView(e.code().name(), e.getMessage()));
    }
}
