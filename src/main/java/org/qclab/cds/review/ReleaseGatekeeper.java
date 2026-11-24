package org.qclab.cds.review;

import org.qclab.cds.audit.AuditLedger;
import org.qclab.cds.audit.ChainVerification;
import org.qclab.cds.domain.Batch;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.domain.RawInjection;
import org.qclab.cds.domain.ReviewSignature;
import org.qclab.cds.domain.Operator;
import org.qclab.cds.domain.Role;
import org.qclab.cds.service.IngestService;
import org.qclab.cds.service.ReviewService;
import org.qclab.cds.store.LabRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 放行门槛：决定一个批次现在能不能放。
 *
 * <p>这是整条控制链的最后一环，也是唯一有权威说"可以放"的地方。
 * 它<b>不做主观判断</b>，只核实那些客观可查的条件：
 * <ol>
 *   <li>审计链完整；</li>
 *   <li>计划与序号对得上（没有缺失的针）；</li>
 *   <li>每个有效进样的最新版本都有<b>密码学上有效</b>的复核签名；</li>
 *   <li>复核人不是该结果的分析员；</li>
 *   <li>没有未解释的 CRITICAL 发现。</li>
 * </ol>
 *
 * <p>注意第 5 条的措辞是"未解释"：系统允许复核人对某条发现给出理由后放行
 * （{@link FindingDisposition}），因为最终判断必须由有资质的人做出。
 * 但"接受并说明"这个动作本身会进入审计链——复核人不能悄悄忽略任何东西。
 */
public final class ReleaseGatekeeper {

    private final LabRepository repo;
    private final AuditLedger audit;
    private final ReviewService reviews;
    private final IngestService ingest;
    private final AnomalyDetector detector;

    public ReleaseGatekeeper(LabRepository repo, AuditLedger audit, ReviewService reviews,
                             IngestService ingest, AnomalyDetector detector) {
        this.repo = repo;
        this.audit = audit;
        this.reviews = reviews;
        this.ingest = ingest;
        this.detector = detector;
    }

    /**
     * 评估放行资格。只读，不改变任何状态；结果会写入审计（"谁在什么时候申请放行"本身也要留痕）。
     */
    public ReleaseGate evaluate(String batchId, Operator requester) {
        Batch batch = repo.batch(batchId)
                .orElseThrow(() -> new IllegalArgumentException("不存在的批次: " + batchId));
        List<ReleaseGate.Check> checks = new ArrayList<>();

        // 1. 审计链
        ChainVerification cv = audit.verify();
        checks.add(cv.intact()
                ? ReleaseGate.Check.pass("AUDIT_CHAIN", "审计链完整", cv.summary())
                : ReleaseGate.Check.fail("AUDIT_CHAIN", "审计链完整", cv.summary()));

        // 2. 计划对账
        IngestService.Reconciliation rec = ingest.reconcile(batchId);
        if (rec.balanced()) {
            checks.add(ReleaseGate.Check.pass("PLAN_RECONCILED", "计划进样全部到账、序号连续",
                    "计划 " + rec.plannedCount() + " 针，已封存 " + rec.sealedCount() + " 针"));
        } else {
            List<String> problems = new ArrayList<>();
            if (!rec.missingPlannedKeys().isEmpty()) {
                problems.add("缺失 " + rec.missingPlannedKeys());
            }
            if (!rec.sequenceGaps().isEmpty()) {
                problems.add("序号跳号 " + rec.sequenceGaps());
            }
            checks.add(ReleaseGate.Check.fail("PLAN_RECONCILED",
                    "计划进样全部到账、序号连续", String.join("；", problems)));
        }

        // 3 & 4. 每个有效进样的最新版本必须有有效签名，且复核人非分析员。
        //        只要求<b>样品/重进样</b>：系统适用性、空白、标准品不进入样品结果，
        //        把它们纳入放行门槛只会制造无意义的阻断。
        List<RawInjection> effective = new ArrayList<>();
        for (RawInjection raw : repo.rawsForBatch(batchId)) {
            if (ingest.effectiveStatus(raw.injectionId()) != RawInjection.RunStatus.COMPLETED) {
                continue;
            }
            if (!requiresReview(raw)) {
                continue;
            }
            effective.add(raw);
        }
        int signed = 0;
        int selfReviewed = 0;
        List<String> unsignedSamples = new ArrayList<>();
        for (RawInjection raw : effective) {
            IntegrationVersion latest = repo.latestVersion(raw.injectionId()).orElse(null);
            if (latest == null) {
                unsignedSamples.add(String.valueOf(raw.sampleId()) + "(未积分)");
                continue;
            }
            ReviewSignature valid = null;
            for (ReviewSignature s : repo.signaturesForVersion(latest.versionId())) {
                // 判定"当前有效"必须同时满足：链上状态 VALID、可验签、且是该进样最新版本
                if (reviews.isEffectiveForCurrentVersion(s)) {
                    valid = s;
                    break;
                }
            }
            if (valid == null) {
                unsignedSamples.add(String.valueOf(raw.sampleId()) + "/v" + latest.versionNo());
            } else {
                signed++;
                if (valid.reviewerId().equals(latest.createdBy())) {
                    selfReviewed++;
                }
            }
        }
        checks.add(unsignedSamples.isEmpty()
                ? ReleaseGate.Check.pass("ALL_REVIEWED", "每个有效进样的最新结果均已复核",
                signed + " 条结果有有效复核签名")
                : ReleaseGate.Check.fail("ALL_REVIEWED", "每个有效进样的最新结果均已复核",
                "未复核: " + String.join(", ", unsignedSamples)));
        checks.add(selfReviewed == 0
                ? ReleaseGate.Check.pass("SEGREGATION_OF_DUTIES", "无自我复核", "复核人均非该结果创建人")
                : ReleaseGate.Check.fail("SEGREGATION_OF_DUTIES", "无自我复核",
                "存在 " + selfReviewed + " 条由创建人自己复核的结果"));

        // 5. 未解释的 CRITICAL / HIGH 发现
        ReviewScreening screening = detector.screenBatch(batchId, requester.operatorId());
        List<Finding> unresolved = screening.requiringAttention().stream()
                .filter(f -> !isExplained(f))
                .toList();
        checks.add(unresolved.isEmpty()
                ? ReleaseGate.Check.pass("FINDINGS_RESOLVED", "无可疑发现待解释",
                "共 " + screening.findings().size() + " 条发现，均已解释或属提示级")
                : ReleaseGate.Check.fail("FINDINGS_RESOLVED", "无可疑发现待解释",
                unresolved.size() + " 条未解释: "
                        + unresolved.stream().map(f -> f.code() + "@" + f.sampleId()).distinct().toList()));

        // 6. 请求者角色（提示项：放行最终必须由 QA 执行，这里只是提醒）
        checks.add(ReleaseGate.Check.advisory("REQUESTER_ROLE", "放行申请应由 QA 提出",
                requester.role() == Role.QA,
                "当前申请角色: " + requester.role()));

        boolean releasable = checks.stream().filter(ReleaseGate.Check::blocking).allMatch(ReleaseGate.Check::passed);
        ReleaseGate gate = new ReleaseGate(batchId, releasable, checks);

        TreeMap<String, String> details = new TreeMap<>();
        details.put("releasable", Boolean.toString(releasable));
        for (ReleaseGate.Check c : checks) {
            details.put("check." + c.code(), c.passed() ? "pass" : (c.blocking() ? "BLOCK" : "advisory"));
        }
        details.put("failedChecks", gate.failed().stream().map(ReleaseGate.Check::code).toList().toString());
        audit.record(requester.operatorId(), "RELEASE_GATE_EVALUATED", "batch", batchId, details);

        return gate;
    }

    /**
     * 该进样是否必须经复核才能放行。只针对进入样品结果的进样。
     */
    private static boolean requiresReview(RawInjection raw) {
        return raw.purpose() == org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE
                || raw.purpose() == org.qclab.cds.domain.PlannedInjection.Purpose.REPEAT;
    }

    /**
     * 该发现是否已被复核人"接受并说明"。
     * 说明记录同样来自不可变仓库，无法事后补删。
     */
    private boolean isExplained(Finding f) {
        return repo.explanationsFor(FindingDisposition.keyOf(f)).stream()
                .anyMatch(d -> d.decision() == FindingDisposition.Decision.ACCEPTED);
    }

    /**
     * 执行放行。
     *
     * <p>传入的 {@code gate} 只是"用户看到的评估结果"，<b>不能</b>作为放行依据：
     * 它在界面上渲染之后、用户点下放行之前，完全可能有人又积了一版、又作废了一针。
     * 因此这里<b>重新评估</b>一次，与传入结果不一致即拒绝。
     * 这是典型的 TOCTOU（检查与使用之间状态改变）防护。
     */
    public Batch markReleased(String batchId, Operator releaser, ReleaseGate shownToUser) {
        if (!releaser.role().canRelease()) {
            throw new IllegalStateException("仅 QA 可放行，当前角色: " + releaser.role());
        }
        ReleaseGate fresh = evaluate(batchId, releaser);
        if (!fresh.releasable()) {
            throw new IllegalStateException("门槛未通过，拒绝放行："
                    + String.join("；", fresh.failed().stream().map(ReleaseGate.Check::description).toList()));
        }
        if (shownToUser != null && !shownToUser.eligibilityDigest().equals(fresh.eligibilityDigest())) {
            throw new IllegalStateException(
                    "放行所依据的评估结果已过期——批次状态在用户确认期间发生了变化，请重新评估后再放行");
        }
        Batch batch = repo.batch(batchId).orElseThrow();
        Batch released = batch.withStatus(Batch.BatchStatus.RELEASED);
        repo.saveBatch(released);
        TreeMap<String, String> details = new TreeMap<>();
        details.put("gateReleasable", "true");
        details.put("reevaluated", "true");
        audit.record(releaser.operatorId(), "BATCH_RELEASED", "batch", batchId, details);
        return released;
    }
}
