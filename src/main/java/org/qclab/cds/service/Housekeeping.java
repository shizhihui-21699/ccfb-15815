package org.qclab.cds.service;

import org.qclab.cds.audit.AuditLedger;
import org.qclab.cds.domain.ReviewSignature;
import org.qclab.cds.store.LabRepository;

import java.time.Instant;
import java.util.List;
import java.util.TreeMap;

/**
 * 复核签名的"绑定看护人"。任何使已复核记录发生变化的事件都必须经过这里，
 * 由它把受影响的签名标记为失效并写审计。
 *
 * <p>为什么单独抽出来：失效逻辑必须被<b>所有</b>变更路径调用。如果散落在积分、作废、
 * 方法变更各处，总有一天会漏掉一条路径，漏掉的那条就是绕过复核的后门。
 */
public final class Housekeeping {

    private final LabRepository repo;
    private final AuditLedger audit;
    private final AuditLedger.Clock clock;

    public Housekeeping(LabRepository repo, AuditLedger audit, AuditLedger.Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 某进样产生新版本后调用：该进样历史版本上的有效签名全部标记为<b>被取代</b>。
     *
     * <p>用 SUPERSEDED 而不是 INVALIDATED：旧签名在密码学上依然有效（它确实签的是
     * 那一版结果），只是不再代表当前结果。分开记录让审计能区分
     * "正常的重新积分"与"篡改后伪造复核"——后者应当让验签失败，前者不应。
     */
    public void onVersionChanged(String injectionId, String newVersionId, String actor) {
        for (ReviewSignature s : repo.signaturesForBatchVersionsOf(injectionId)) {
            if (s.status() != ReviewSignature.Status.VALID) {
                continue;
            }
            String reason = "进样 " + injectionId + " 产生了新的积分版本 " + newVersionId
                    + "，此签名所复核的结果已被取代";
            ReviewSignature superseded = s.supersede(clock.now(), reason);
            repo.appendSignature(superseded);
            TreeMap<String, String> details = new TreeMap<>();
            details.put("versionId", s.versionId());
            details.put("reason", reason);
            details.put("boundPayloadHash", s.boundPayloadHash());
            audit.record(actor, "REVIEW_SIGNATURE_SUPERSEDED", "reviewSignature",
                    s.signatureId(), details);
        }
    }

    /** 某进样被作废/恢复后调用：与它相关的所有有效签名失效（结果不再可用）。 */
    public void onDispositionChanged(String injectionId, String actor, String reason) {
        for (ReviewSignature s : repo.signaturesForBatchVersionsOf(injectionId)) {
            if (s.status() == ReviewSignature.Status.VALID) {
                invalidate(s, reason, actor);
            }
        }
    }

    public void invalidate(ReviewSignature s, String reason, String actor) {
        ReviewSignature invalidated = s.invalidate(clock.now(), reason);
        repo.appendSignature(invalidated);
        TreeMap<String, String> details = new TreeMap<>();
        details.put("versionId", s.versionId());
        details.put("reason", reason);
        details.put("boundPayloadHash", s.boundPayloadHash());
        audit.record(actor, "REVIEW_SIGNATURE_INVALIDATED", "reviewSignature",
                s.signatureId(), details);
    }
}
