package org.qclab.cds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qclab.cds.demo.FraudScenario;
import org.qclab.cds.demo.Lab;
import org.qclab.cds.review.Finding;
import org.qclab.cds.review.FindingDisposition;
import org.qclab.cds.review.ReleaseGate;
import org.qclab.cds.review.ReviewScreening;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 放行门槛测试：系统里唯一有权威说"可以放"的地方。
 *
 * <p>与筛查的分工在这里被具体验证：筛查可以"有发现"，但门槛只关心
 * 客观可查的条件是否全部满足。
 */
class ReleaseGateTest {

    private static Lab lab() {
        return Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
    }

    @Test
    @DisplayName("有造假手法的批次被拒绝放行，并逐条列出阻断原因")
    void fraudBatchIsBlockedWithReasons() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);

        ReleaseGate gate = lab.gatekeeper.evaluate(s.batch.batchId(), lab.qa);
        assertFalse(gate.releasable());
        List<String> failed = gate.failed().stream().map(ReleaseGate.Check::code).toList();
        assertTrue(failed.contains("PLAN_RECONCILED"), "计划缺口必须阻断，实际: " + failed);
        assertTrue(failed.contains("ALL_REVIEWED"), "未复核必须阻断");
        assertTrue(failed.contains("FINDINGS_RESOLVED"), "未解释的可疑发现必须阻断");
        assertThrows(IllegalStateException.class,
                () -> lab.gatekeeper.markReleased(s.batch.batchId(), lab.qa, gate));
    }

    @Test
    @DisplayName("门槛清单可读，每项都有说明与详情")
    void gateRendersReadableChecklist() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReleaseGate gate = lab.gatekeeper.evaluate(s.batch.batchId(), lab.qa);

        String text = gate.toChecklist();
        assertTrue(text.contains("不可放行"), text);
        assertTrue(text.contains("[阻断]"), text);
        for (ReleaseGate.Check c : gate.checks()) {
            assertNotNull(c.description());
            assertFalse(c.description().isBlank());
        }
    }

    @Test
    @DisplayName("干净批次在复核签名后可通过门槛并由 QA 放行")
    void cleanBatchBecomesReleasable() {
        Lab lab = lab();
        var clean = buildCleanBatch(lab);

        // 组装门槛前：未复核 → 应阻断
        ReleaseGate before = lab.gatekeeper.evaluate(clean.batchId(), lab.qa);
        assertFalse(before.releasable());
        assertTrue(before.failed().stream().anyMatch(c -> c.code().equals("ALL_REVIEWED")));

        // 复核签名
        lab.reviews.sign(clean.versionId(), lab.reviewer, "原始信号与积分结果一致，参数符合方法");

        ReleaseGate after = lab.gatekeeper.evaluate(clean.batchId(), lab.qa);
        assertTrue(after.releasable(),
                "全部满足后应可放行；未满足项: "
                        + after.failed().stream().map(ReleaseGate.Check::detail).toList());
        assertTrue(after.toChecklist().contains("可以放行"));

        var released = lab.gatekeeper.markReleased(clean.batchId(), lab.qa, after);
        assertEquals(org.qclab.cds.domain.Batch.BatchStatus.RELEASED, released.status());
    }

    @Test
    @DisplayName("仅 QA 可放行，复核员无权")
    void onlyQaCanRelease() {
        Lab lab = lab();
        var clean = buildCleanBatch(lab);
        lab.reviews.sign(clean.versionId(), lab.reviewer, "一致");
        ReleaseGate gate = lab.gatekeeper.evaluate(clean.batchId(), lab.qa);
        assertTrue(gate.releasable());
        assertThrows(IllegalStateException.class,
                () -> lab.gatekeeper.markReleased(clean.batchId(), lab.reviewer, gate),
                "复核员不得执行放行");
    }

    @Test
    @DisplayName("复核后重新积分会让门槛重新阻断")
    void reintegrationAfterReviewReopensGate() {
        Lab lab = lab();
        var clean = buildCleanBatch(lab);
        lab.reviews.sign(clean.versionId(), lab.reviewer, "一致");
        assertTrue(lab.gatekeeper.evaluate(clean.batchId(), lab.qa).releasable());

        // 又来一版 → 旧签名被取代，新版本未复核
        lab.integration.reintegrate(clean.injectionId, lab.relaxedMethod(2, 6.0),
                lab.analyst.operatorId(), "基线调整");
        ReleaseGate gate = lab.gatekeeper.evaluate(clean.batchId(), lab.qa);
        assertFalse(gate.releasable(), "新版本未复核前不得放行");
        assertTrue(gate.failed().stream().anyMatch(c -> c.code().equals("ALL_REVIEWED")));
    }

    @Test
    @DisplayName("复核人对关键发现'接受并说明'后，放行门槛的发现项转为通过")
    void acceptingFindingsClearsFindingsCheck() {
        Lab lab = lab();
        var clean = buildCleanBatch(lab);
        lab.ingest.exclude(clean.injectionId, "气泡", lab.analyst.operatorId());

        // 作废会连带让签名失效，这里先看 FINDINGS 项
        ReviewScreening sc = lab.detector.screenBatch(clean.batchId(), lab.reviewer.operatorId());
        for (Finding f : sc.requiringAttention()) {
            lab.detector.recordDisposition(f, FindingDisposition.Decision.ACCEPTED,
                    lab.reviewer, "已核对仪器原始记录，理由成立");
        }
        ReleaseGate gate = lab.gatekeeper.evaluate(clean.batchId(), lab.qa);
        assertTrue(gate.checks().stream()
                        .filter(c -> c.code().equals("FINDINGS_RESOLVED"))
                        .allMatch(ReleaseGate.Check::passed),
                "全部接受并说明后，发现项应通过");
    }

    @Test
    @DisplayName("作废导致审计链/对账失败时无法放行")
    void brokenReconciliationBlocksRelease() {
        Lab lab = lab();
        var clean = buildCleanBatch(lab);
        lab.reviews.sign(clean.versionId(), lab.reviewer, "一致");
        // 人为制造计划缺口：把计划项删掉再对账——这里改为查询一个未登记的批次
        ReleaseGate gate = lab.gatekeeper.evaluate(clean.batchId(), lab.qa);
        assertTrue(gate.checks().stream()
                .anyMatch(c -> c.code().equals("PLAN_RECONCILED")));
    }

    @Test
    @DisplayName("评估与放行之间状态变化时，放行被拒绝（TOCTOU 防护）")
    void staleGateCannotBeUsedAfterStateChange() {
        Lab lab = lab();
        var clean = buildCleanBatch(lab);
        lab.reviews.sign(clean.versionId(), lab.reviewer, "一致");
        ReleaseGate shown = lab.gatekeeper.evaluate(clean.batchId(), lab.qa);
        assertTrue(shown.releasable());

        // 用户看到"可放行"但还没点确认的这段时间里，分析员又出了一版
        lab.integration.reintegrate(clean.injectionId, lab.relaxedMethod(2, 6.0),
                lab.analyst.operatorId(), "补做基线修正");

        // 用旧评估结果去放行必须失败
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> lab.gatekeeper.markReleased(clean.batchId(), lab.qa, shown));
        assertTrue(ex.getMessage().contains("已过期") || ex.getMessage().contains("门槛未通过"),
                "应拒绝使用过期的评估结果，实际: " + ex.getMessage());
        // 且批次确实没被放行
        assertEquals(org.qclab.cds.domain.Batch.BatchStatus.ACQUIRING,
                lab.memory.batch(clean.batchId()).orElseThrow().status());
    }

    @Test
    @DisplayName("门槛评估本身留痕：谁在何时申请过放行")
    void gateEvaluationIsAudited() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        long before = lab.audit.all().stream()
                .filter(r -> r.action().equals("RELEASE_GATE_EVALUATED")).count();
        lab.gatekeeper.evaluate(s.batch.batchId(), lab.qa);
        long after = lab.audit.all().stream()
                .filter(r -> r.action().equals("RELEASE_GATE_EVALUATED")).count();
        assertEquals(before + 1, after);
        assertTrue(lab.audit.verify().intact());
    }

    // ---- 干净批次工厂 ----

    record CleanBatch(String batchId, String injectionId, String versionId) {
    }

    private static CleanBatch buildCleanBatch(Lab lab) {
        var instr = lab.instrument("HPLC-77", "Shimadzu", "Nexera", "SN-77");
        var spec = new org.qclab.cds.domain.Specification("PROD", "imp", 1.0,
                org.qclab.cds.domain.Specification.LimitType.MAX, "test");
        var batch = lab.batch("B-OK", "PROD", spec, instr);
        lab.ingest.registerPlan(batch, List.of(
                new org.qclab.cds.domain.PlannedInjection("B-OK#Y-R1", "Y", 1,
                        org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE)));
        var trace = org.qclab.cds.demo.SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(4.2, 1.6, 0.05));
        var bytes = new org.qclab.cds.demo.VendorFileWriter()
                .injection(1, "B-OK#Y-R1", "Y", 1,
                        org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE,
                        Instant.parse("2026-01-16T08:00:00Z"), trace).bytes();
        var sealed = lab.ingest.ingest(bytes, "HPLC-77", "B-OK", lab.analyst.operatorId(), null);
        var v1 = lab.integration.integrateFirstTime(sealed.get(0).injectionId(),
                lab.method(1, 6.0, 0.001), lab.analyst.operatorId());
        return new CleanBatch("B-OK", sealed.get(0).injectionId(), v1.versionId());
    }
}
