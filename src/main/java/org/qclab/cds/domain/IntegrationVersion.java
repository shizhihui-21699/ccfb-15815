package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canon;
import org.qclab.cds.crypto.Canonical;

import java.time.Instant;

/**
 * 一个积分版本。系统里<b>没有"更新结果"这个操作</b>，只有"追加新版本"。
 *
 * <p>{@code parentVersionId} 把同一进样的所有积分串成一条链。复核人看这一针时
 * 看到的是链上每一版的差异，而不是最后一版——这正是"改积分参数让杂质峰消失"
 * 无法隐藏的原因：消失的峰在父版本里还在，系统会自动对比并高亮。
 *
 * @param resultHash   结果快照的哈希，供复核签名绑定
 * @param methodHash   积分方法快照的哈希（含参数），与结果一并冻结
 */
public record IntegrationVersion(
        String versionId,
        String injectionId,
        String parentVersionId,
        int versionNo,
        String methodSnapshotJson,
        String methodHash,
        String resultJson,
        String resultHash,
        String createdBy,
        Instant createdAt,
        String reason
) implements Canonical {

    public IntegrationVersion {
        if (versionNo < 1) {
            throw new IllegalArgumentException("版本号从 1 开始");
        }
        if (versionNo > 1 && parentVersionId == null) {
            throw new IllegalArgumentException("非首版必须指明父版本");
        }
        if (versionNo == 1 && parentVersionId != null) {
            throw new IllegalArgumentException("首版不能有父版本");
        }
        if (versionNo > 1 && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("重新积分必须写明原因");
        }
    }

    public IntegrationMethod method() {
        return org.qclab.cds.crypto.Json.parse(methodSnapshotJson, IntegrationMethod.class);
    }

    public IntegrationResult result() {
        return org.qclab.cds.crypto.Json.parse(resultJson, IntegrationResult.class);
    }

    // canonicalForm() 使用 Canonical 的默认实现（Json.canonical），
    // 确保 contentHash() 与其它所有哈希路径使用同一种规范化表示。

    /** 复核签名要绑定的内容：结果 + 方法 + 来源进样的哈希。 */
    public String reviewBindingPayload(String injectionRawHash) {
        return Canon.kv(
                "versionId", versionId,
                "resultHash", resultHash,
                "methodHash", methodHash,
                "injectionRawHash", injectionRawHash
        );
    }
}
