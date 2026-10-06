package com.github.lechandonga.monoreporeleasetrainjava.web;

import com.github.lechandonga.monoreporeleasetrainjava.model.ReleaseBatch;
import com.github.lechandonga.monoreporeleasetrainjava.model.ReleaseItem;

import java.util.List;

/**
 * REST 响应体。
 */
public final class Responses {

    private Responses() {
    }

    public record ItemView(
            String packageName,
            String changeLevel,
            boolean cascaded,
            String fromVersion,
            String targetVersion,
            String status,
            String unreleasableReason,
            String failureReason) {

        static ItemView from(ReleaseItem item) {
            return new ItemView(
                    item.packageName(),
                    item.changeLevel().name(),
                    item.cascaded(),
                    item.fromVersion() == null ? null : item.fromVersion().toString(),
                    item.targetVersion() == null ? null : item.targetVersion().toString(),
                    item.status().name(),
                    item.unreleasableReason(),
                    item.failureReason());
        }
    }

    public record BatchView(
            String batchId,
            String state,
            String createdAt,
            String updatedAt,
            List<ItemView> items) {

        public static BatchView from(ReleaseBatch batch) {
            return new BatchView(
                    batch.id(),
                    batch.state().name(),
                    batch.createdAt().toString(),
                    batch.updatedAt().toString(),
                    batch.items().stream().map(ItemView::from).toList());
        }
    }

    public record ErrorView(String code, String message) {
    }
}
