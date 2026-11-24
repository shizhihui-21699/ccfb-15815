package org.qclab.cds.service;

import org.qclab.cds.audit.AuditLedger;
import org.qclab.cds.crypto.Hashing;
import org.qclab.cds.crypto.Keyring;
import org.qclab.cds.crypto.Signer;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.domain.Operator;
import org.qclab.cds.domain.RawInjection;
import org.qclab.cds.domain.ReviewSignature;
import org.qclab.cds.domain.Role;
import org.qclab.cds.store.LabRepository;

import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 复核服务：电子签名 + 绑定校验。
 *
 * <p>签名绑定链：{@code 版本结果哈希 + 方法哈希 + 原始进样哈希}。
 * 三者任一变化都会让 {@link #verify} 返回 {@code false}，且 {@link #sweepInvalidations}
 * 会把该签名标为失效。因此"复核之后再去改积分"或"替换原始信号"都会立刻暴露。
 *
 * <p>职责分离：分析员不能复核自己的结果（同一个人既做又查等于没查）。
 */
public final class ReviewService {

    private final LabRepository repo;
    private final AuditLedger audit;
    private final Keyring keyring;
    private final Housekeeping housekeeping;
    private final AuditLedger.Clock clock;

    public ReviewService(LabRepository repo, AuditLedger audit, Keyring keyring,
                         Housekeeping housekeeping, AuditLedger.Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.keyring = keyring;
        this.housekeeping = housekeeping;
        this.clock = clock;
    }

    /**
     * 复核并签名某一版结果。
     *
     * @throws IllegalStateException 若版本不存在、复核人无权限、或复核人即创建人
     */
    public ReviewSignature sign(String versionId, Operator reviewer, String comment) {
        IntegrationVersion version = repo.version(versionId)
                .orElseThrow(() -> new IllegalArgumentException("不存在的版本: " + versionId));
        if (!reviewer.role().canReview()) {
            throw new IllegalStateException("角色 " + reviewer.role() + " 无复核权限");
        }
        if (reviewer.operatorId().equals(version.createdBy())) {
            throw new IllegalStateException("不得复核自己创建的结果（职责分离）");
        }
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("复核必须留下意见");
        }

        RawInjection raw = repo.raw(version.injectionId())
                .orElseThrow(() -> new IllegalStateException("版本缺少来源进样"));
        String payload = version.reviewBindingPayload(raw.contentHash());
        String payloadHash = Hashing.sha256Hex(payload);
        String sigB64 = Signer.sign(keyring.privateKey(reviewer.operatorId()), payload);

        ReviewSignature signature = new ReviewSignature(
                "SIG-" + UUID.randomUUID(),
                versionId,
                reviewer.operatorId(),
                reviewer.role().name(),
                ReviewSignature.Status.VALID,
                payloadHash,
                sigB64,
                clock.now(),
                comment,
                null,
                null
        );
        repo.appendSignature(signature);

        TreeMap<String, String> details = new TreeMap<>();
        details.put("versionId", versionId);
        details.put("resultHash", version.resultHash());
        details.put("methodHash", version.methodHash());
        details.put("injectionRawHash", raw.contentHash());
        details.put("boundPayloadHash", payloadHash);
        details.put("comment", comment);
        audit.record(reviewer.operatorId(), "REVIEW_SIGNED", "reviewSignature",
                signature.signatureId(), details);

        return signature;
    }

    /** 当前有效的签名（若有）：链上状态 VALID、可验签、且针对的是最新版本。 */
    public Optional<ReviewSignature> currentValidSignature(String versionId) {
        return repo.signaturesForVersion(versionId).stream()
                .filter(s -> s.status() != ReviewSignature.Status.WITHDRAWN)
                .filter(this::isEffectiveForCurrentVersion)
                .findFirst();
    }

    public List<ReviewSignature> history(String versionId) {
        return repo.signaturesForVersion(versionId);
    }

    /**
     * 独立验签：只回答"这个签名在密码学上是否与<b>它自己绑定的载荷</b>相符"。
     *
     * <p>它刻意<b>不</b>看 {@code status} 字段，也不判断"该版本是否仍是当前版本"。
     * 这两件事属于簿记，而簿记可能被绕过应用层改写。把它们混进验签会让
     * "签名是否可信"与"签名是否当前"两个不同的问题纠缠在一起，
     * 掩盖真正的篡改。
     *
     * <p>要判断一个签名是否<b>当前有效</b>，请用 {@link #effectiveStatus} 与
     * {@link #isEffectiveForCurrentVersion}。
     */
    public VerificationResult verify(ReviewSignature signature) {
        IntegrationVersion version = repo.version(signature.versionId()).orElse(null);
        if (version == null) {
            return VerificationResult.invalid("版本不存在", signature);
        }
        RawInjection raw = repo.raw(version.injectionId()).orElse(null);
        if (raw == null) {
            return VerificationResult.invalid("来源进样不存在", signature);
        }
        String payload = version.reviewBindingPayload(raw.contentHash());
        String currentPayloadHash = Hashing.sha256Hex(payload);
        if (!currentPayloadHash.equals(signature.boundPayloadHash())) {
            return VerificationResult.invalid(
                    "签名绑定的载荷已变化（结果/方法/原始数据被改动）", signature);
        }
        Optional<PublicKey> pk = keyring.publicKey(signature.reviewerId());
        if (pk.isEmpty()) {
            return VerificationResult.invalid("复核人公钥不存在", signature);
        }
        if (!Signer.verify(pk.get(), payload, signature.signatureB64())) {
            return VerificationResult.invalid("密码学签名验证失败", signature);
        }
        return VerificationResult.valid(signature);
    }

    /**
     * 从<b>审计链</b>重放某个签名的当前状态。
     *
     * <p>为什么不直接读签名表的 status 列：那是可以绕过应用层直改的簿记字段。
     * 审计链是 append-only 且逐条哈希串联的，任何一条状态变更都在链上。
     * 因此"这个签名现在到底是不是有效"的权威答案必须来自链，而不是来自一个
     * UPDATE 得动的列。签名表里的 status 降级为展示用的缓存。
     *
     * <p>如果链上对该签名<b>没有任何状态变更记录</b>，说明它自签署以来未被取代或撤回。
     */
    public ReviewSignature.Status effectiveStatus(ReviewSignature signature) {
        ReviewSignature.Status status = ReviewSignature.Status.VALID;
        for (org.qclab.cds.audit.AuditRecord r : audit.all()) {
            if (!r.entityId().equals(signature.signatureId())) {
                continue;
            }
            switch (r.action()) {
                case "REVIEW_SIGNATURE_SUPERSEDED" -> status = ReviewSignature.Status.SUPERSEDED;
                case "REVIEW_SIGNATURE_INVALIDATED" -> status = ReviewSignature.Status.INVALIDATED;
                case "REVIEW_SIGNATURE_WITHDRAWN" -> status = ReviewSignature.Status.WITHDRAWN;
                default -> {
                    // 其它动作不影响签名状态
                }
            }
        }
        return status;
    }

    /**
     * 该签名是否"当前有效"：密码学可验证、链上状态为 VALID、且所签版本
     * 仍是该进样的最新版本。
     */
    public boolean isEffectiveForCurrentVersion(ReviewSignature signature) {
        if (effectiveStatus(signature) != ReviewSignature.Status.VALID) {
            return false;
        }
        if (!verify(signature).valid()) {
            return false;
        }
        return repo.latestVersion(repo.version(signature.versionId())
                        .map(IntegrationVersion::injectionId).orElse(""))
                .map(v -> v.versionId().equals(signature.versionId()))
                .orElse(false);
    }

    /**
     * 扫描并失效：链上状态仍为 VALID、但密码学验证已不成立的签名。
     *
     * <p>这捕获的是"记录被篡改后签名无法再验证"——最高级告警。
     * 注意它<b>不</b>处理 SUPERSEDED（那是正常的重新积分，不是篡改）。
     */
    public List<ReviewSignature> sweepInvalidations(String actor) {
        List<ReviewSignature> invalidated = new ArrayList<>();
        for (ReviewSignature s : repo.allSignatures()) {
            if (effectiveStatus(s) != ReviewSignature.Status.VALID) {
                continue;
            }
            VerificationResult vr = verify(s);
            if (!vr.valid()) {
                housekeeping.invalidate(s, vr.reason(), actor);
                invalidated.add(s);
            }
        }
        return invalidated;
    }

    public record VerificationResult(boolean valid, String reason, ReviewSignature signature) {
        static VerificationResult valid(ReviewSignature s) {
            return new VerificationResult(true, "有效", s);
        }

        static VerificationResult invalid(String reason, ReviewSignature s) {
            return new VerificationResult(false, reason, s);
        }
    }
}
