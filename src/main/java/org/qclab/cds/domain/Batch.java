package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

import java.time.Instant;
import java.util.List;

/**
 * 批次：一组待放行的样品 + 所用规格。
 * 复核以批次为单位组织，因为监管关注的正是"这一批放行了没有"。
 */
public record Batch(
        String batchId,
        String productId,
        Specification specification,
        String instrumentId,
        List<String> plannedKeys,
        String createdBy,
        Instant createdAt,
        BatchStatus status
) implements Canonical {

    public enum BatchStatus {
        /** 采集中。 */
        ACQUIRING,
        /** 采数完成，等待复核。 */
        PENDING_REVIEW,
        /** 复核通过。 */
        REVIEWED,
        /** QA 已放行。 */
        RELEASED,
        /** 被拒绝放行。 */
        REJECTED
    }

    public Batch {
        plannedKeys = plannedKeys == null ? List.of() : List.copyOf(plannedKeys);
    }

    public Batch withStatus(BatchStatus s) {
        return new Batch(batchId, productId, specification, instrumentId, plannedKeys,
                createdBy, createdAt, s);
    }
}
