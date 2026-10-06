package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.web;

import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.dto.ReleaseRequest;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.ReleaseBatch;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service.ReleaseTrainService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发布列车 HTTP 接口（本地）：
 * POST /api/release-trains        提交/幂等重放整批发布
 * POST /api/release-trains/{id}/retry  对中途失败的批次做确定性重试
 * GET  /api/release-trains/{id}        查询整批进度与每个包的状态
 */
@RestController
@RequestMapping("/api/release-trains")
public class ReleaseTrainController {

    private final ReleaseTrainService service;

    public ReleaseTrainController(ReleaseTrainService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ReleaseBatch> submit(@RequestBody ReleaseRequest request) {
        return ResponseEntity.ok(service.submit(request));
    }

    @PostMapping("/{batchId}/retry")
    public ReleaseBatch retry(@PathVariable String batchId) {
        return service.retry(batchId);
    }

    @GetMapping("/{batchId}")
    public ReleaseBatch status(@PathVariable String batchId) {
        return service.getBatch(batchId);
    }
}
