package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

import java.time.Instant;

/**
 * 复核签名：把复核人的电子签名绑定到"某一版结果 + 其来源原始数据"的哈希上。
 *
 * <p>这是本系统防"复核后偷改"的核心机制。签名后任何底层变化——重新积分、
 * 信号被替换、方法被修改——都会改变 {@code boundPayloadHash}，
 * 使签名立即失效并由 {@link org.qclab.cds.review.ReviewService} 标记为
 * {@link Status#INVALIDATED}，把该结果重新推回复核队列。
 * 签名无法被"搬"到另一条记录上，因为 payload 里含版本 ID。
 */
public record ReviewSignature(
        String signatureId,
        String versionId,
        String reviewerId,
        String reviewerRole,
        Status status,
        String boundPayloadHash,
        String signatureB64,
        Instant signedAt,
        String comment,
        Instant invalidatedAt,
        String invalidationReason
) implements Canonical {

    public enum Status {
        /** 签名有效且所绑定的记录未变，且该版本仍是该进样的当前版本。 */
        VALID,
        /**
         * 签名后该进样产生了新版本，此签名所针对的结果已被取代。
         *
         * <p>注意与 {@link #INVALIDATED} 的区别：被取代的签名在<b>密码学上仍然有效</b>——
         * 它当初确实签的是那一版结果。它失效的原因是"不再是当前结果"，而非"记录被改过"。
         * 把两者混为一谈会让审计无法区分"正常重新积分"与"篡改后伪造复核"。</p>
         */
        SUPERSEDED,
        /** 签名绑定的记录内容发生变化（篡改），密码学验证失败。 */
        INVALIDATED,
        /** 复核人主动撤回（例如复核时误判）。 */
        WITHDRAWN
    }

    public ReviewSignature {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("复核必须指向具体版本");
        }
    }

    // canonicalForm() 使用 Canonical 的默认实现（Json.canonical），
    // 确保 contentHash() 与其它所有哈希路径使用同一种规范化表示。

    public ReviewSignature invalidate(Instant at, String reason) {
        return withStatus(Status.INVALIDATED, at, reason);
    }

    public ReviewSignature supersede(Instant at, String reason) {
        return withStatus(Status.SUPERSEDED, at, reason);
    }

    private ReviewSignature withStatus(Status newStatus, Instant at, String reason) {
        return new ReviewSignature(signatureId, versionId, reviewerId, reviewerRole, newStatus,
                boundPayloadHash, signatureB64, signedAt, comment, at, reason);
    }
}
