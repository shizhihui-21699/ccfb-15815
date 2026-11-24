package org.qclab.cds.cli;

import org.qclab.cds.audit.AuditRecord;
import org.qclab.cds.audit.ChainVerification;
import org.qclab.cds.demo.FraudScenario;
import org.qclab.cds.demo.Lab;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.domain.Peak;
import org.qclab.cds.domain.RawInjection;
import org.qclab.cds.domain.ReviewSignature;
import org.qclab.cds.review.Finding;
import org.qclab.cds.review.ReleaseGate;
import org.qclab.cds.review.ReviewScreening;
import org.qclab.cds.service.PeakDelta;

import java.time.Instant;
import java.util.List;

/**
 * 演示程序：完整走一遍"三种造假手法 → 被捕获 → 被阻断放行"的过程。
 *
 * <p>运行：{@code gradle run} 或 {@code java -jar build/libs/qclab-cds-0.1.0.jar}
 *
 * <p>它的用途不是产品界面，而是让合规/QA 人员能亲手看到：
 * 每一次操作在审计链上留下什么、复核人打开界面时看到的是哪几条。
 */
public final class Main {

    private static final String RULE = "=".repeat(78);

    public static void main(String[] args) {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);

        section("1. 本次批次", () -> {
            System.out.println("批次: " + s.batch.batchId() + "  产品: " + s.batch.productId());
            System.out.println("仪器: " + s.instrument.instrumentId() + " (" + s.instrument.vendor() + " "
                    + s.instrument.model() + ")");
            System.out.println("规格: 单个杂质 ≤ " + FraudScenario.SPEC_LIMIT + "%");
            System.out.println();
            System.out.println("已封存的原始进样（不可修改、不可删除）：");
            for (RawInjection r : lab.memory.rawsForBatch(s.batch.batchId())) {
                System.out.printf("  序号 %d  %-8s run%d  %-20s  信号哈希 %s%n",
                        r.sequenceNo(), r.sampleId(), r.runNumber(), r.purpose(),
                        shortHash(r.traceHash()));
            }
        });

        section("2. 计划对账：删掉的那一针自己会说话", () -> {
            var rec = s.reconciliation();
            System.out.println("计划针数: " + rec.plannedCount() + "  已封存: " + rec.sealedCount());
            System.out.println("缺失的计划项: " + (rec.missingPlannedKeys().isEmpty()
                    ? "无" : rec.missingPlannedKeys()));
            System.out.println("仪器序号空洞: " + (rec.sequenceGaps().isEmpty()
                    ? "无" : rec.sequenceGaps()));
            System.out.println();
            System.out.println("说明：分析员只导出了重进样，压着头文件里的序号；");
            System.out.println("      但计划里有、账本里没有的第一针，和序号 3 的空洞，两处都对不上。");
        });

        section("3. 积分版本链：改参数让峰消失，旧版本仍在", () -> {
            System.out.println("S-002 进样的版本链：");
            List<IntegrationVersion> versions = lab.memory.versionsForInjection(s.s002Injection.injectionId());
            for (IntegrationVersion v : versions) {
                System.out.printf("  v%d  %s  杂质=%.4f%%  峰数=%d  原因=%s%n",
                        v.versionNo(), shortHash(v.versionId()),
                        v.result().impurityPercent(), v.result().peaks().size(), v.reason());
            }
            PeakDelta delta = PeakDelta.compute(s.s002Strict, s.s002Relaxed);
            System.out.println();
            System.out.println("v1 → v2 的差异：");
            System.out.println("  消失的峰: " + delta.disappeared().size());
            for (Peak p : delta.disappeared()) {
                System.out.printf("    rt=%.3f min  area=%.1f  type=%s%n",
                        p.retentionTimeMin(), p.area(), p.peakType());
            }
            System.out.printf("  杂质百分比: %.4f%% → %.4f%%%n",
                    delta.impurityPercentBefore(), delta.impurityPercentAfter());
        });

        section("4. 审计链：每一次动作都在链上", () -> {
            List<AuditRecord> all = lab.audit.all();
            System.out.println("审计记录 " + all.size() + " 条，链头 " + shortHash(lab.audit.headHash()));
            System.out.println("关键动作序列：");
            for (AuditRecord r : all) {
                if (List.of("PLAN_REGISTERED", "INJECTION_SEALED", "INTEGRATION_V1",
                        "REINTEGRATION", "INJECTION_EXCLUDED", "PLAN_GAP_DETECTED",
                        "REVIEW_SIGNED").contains(r.action())) {
                    System.out.printf("  #%3d %s %-22s %-10s %s%n",
                            r.sequence(), r.timestamp(), r.action(), r.actor(),
                            shortHash(r.recordHash()));
                }
            }
            ChainVerification cv = lab.audit.verify();
            System.out.println();
            System.out.println("链校验: " + cv.summary());
        });

        section("5. 复核人的界面：可疑记录自动排好序", () -> {
            ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());
            System.out.println("共 " + sc.findings().size() + " 条，按严重度: " + sc.countsBySeverity());
            System.out.println("是否需要阻断: " + (sc.blocking() ? "是" : "否"));
            System.out.println();
            System.out.println("── 必须先处理的 ──");
            for (Finding f : sc.requiringAttention()) {
                System.out.printf("[%s] %-32s %s%n", f.severity(), f.code(), f.title());
                System.out.println("        样本: " + f.sampleId() + "   " + f.detail());
            }
            System.out.println();
            System.out.println("── 可以批量确认的 ──");
            for (Finding f : sc.informational()) {
                System.out.printf("[%s] %-32s %s%n", f.severity(), f.code(), f.title());
            }
        });

        section("6. 复核签名：签完之后再改，签名立刻失效", () -> {
            IntegrationVersion s004v1 = lab.memory.latestVersion(s.s004Injection.injectionId()).orElseThrow();
            ReviewSignature sig = lab.reviews.sign(s004v1.versionId(), lab.reviewer, "原始信号与结果一致");
            System.out.println("复核员签名: " + sig.signatureId().substring(0, 12)
                    + "  绑定载荷 " + shortHash(sig.boundPayloadHash()));
            System.out.println("验签: " + lab.reviews.verify(sig).reason());
            System.out.println("对当前版本有效: " + lab.reviews.isEffectiveForCurrentVersion(sig));

            IntegrationVersion v2 = lab.integration.reintegrate(s.s004Injection.injectionId(),
                    lab.relaxedMethod(4, 6.0), lab.analyst.operatorId(), "基线漂移，重新积分");
            System.out.println();
            System.out.println("分析员随后又积了一版 v" + v2.versionNo() + " →");
            System.out.println("  该签名的链上状态: " + lab.reviews.effectiveStatus(sig));
            System.out.println("  密码学仍有效（它确实签的是 v1）: " + lab.reviews.verify(sig).valid());
            System.out.println("  但不再是当前版本的复核: " + lab.reviews.isEffectiveForCurrentVersion(sig));
            System.out.println("  → 放行门槛会自动重新要求复核 v" + v2.versionNo());
        });

        section("7. 放行门槛：唯一的'可以放'裁定者", () -> {
            ReleaseGate gate = lab.gatekeeper.evaluate(s.batch.batchId(), lab.qa);
            System.out.print(gate.toChecklist());
        });

        section("8. 结论", () -> {
            System.out.println("三种手法全部被捕获，且证据可独立核验：");
            System.out.println("  A 删针  → 计划缺口 + 序号跳号（双证据）");
            System.out.println("  B 改参数 → 版本链对比列出消失的峰 + 由不合格变合格");
            System.out.println("  C 出结果后作废 → 作废动作与首版积分的时间先后被标出");
            System.out.println();
            System.out.println("正常样品 S-004 不产生任何 CRITICAL/HIGH 发现——");
            System.out.println("复核人只需看顶部少数几条，而不是逐条通读。");
        });
    }

    private static void section(String title, Runnable body) {
        System.out.println();
        System.out.println(RULE);
        System.out.println(title);
        System.out.println(RULE);
        body.run();
    }

    private static String shortHash(String h) {
        return h == null ? "-" : h.substring(0, Math.min(12, h.length()));
    }
}
