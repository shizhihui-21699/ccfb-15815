package org.qclab.cds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qclab.cds.demo.FraudScenario;
import org.qclab.cds.demo.Lab;
import org.qclab.cds.review.Finding;
import org.qclab.cds.review.ReleaseGate;
import org.qclab.cds.review.ReviewScreening;
import org.qclab.cds.review.RuleCodes;
import org.qclab.cds.review.Severity;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 以监管检查报告的原始描述为验收标准。
 *
 * <p>这个测试类的价值在于：它用"监管发现的那句话"作为断言，
 * 而不是用"系统内部的规则代码"。如果哪天重构改变了规则的实现方式，
 * 只要这三句话仍然成立，系统就仍然解决了问题。
 */
class RegulatoryScenarioAcceptanceTest {

    private static Lab lab() {
        return Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
    }

    @Test
    @DisplayName("'分析员把不合格的检测结果偷偷删掉重测，只保留合格的那一次' —— 必须被独立发现")
    void droppedFailingRunAndKeptPassingRetestIsCaught() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());

        Set<String> critical = sc.findings().stream()
                .filter(f -> f.severity() == Severity.CRITICAL)
                .map(Finding::code).collect(Collectors.toSet());

        // 删针有两条互相独立的证据链，任一条单独成立即可发现
        assertTrue(critical.contains(RuleCodes.PLAN_GAP),
                "计划里登记过、账本里不存在 → 应到未到");
        assertTrue(critical.contains(RuleCodes.SEQUENCE_GAP),
                "仪器序号 3 缺失 → 仪器侧发生过本系统未见的事");

        // 被保留下来的那次重进样本身也带着"没有原因"的标记
        List<Finding> repeatFindings = sc.findings().stream()
                .filter(f -> f.code().equals(RuleCodes.REPEAT_WITHOUT_REASON))
                .filter(f -> "S-001".equals(f.sampleId())).toList();
        assertFalse(repeatFindings.isEmpty(), "保留下来的重进样必须说明为何重测");
        assertEquals(Severity.HIGH, repeatFindings.get(0).severity());
    }

    @Test
    @DisplayName("'手动改积分参数让杂质峰消失' —— 必须留下旧版本并逐峰对比")
    void manualIntegrationChangeThatHidesImpurityIsCaught() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());

        // 1. 旧版本没有被覆盖，两版都还在
        assertEquals(2, lab.memory.versionsForInjection(s.s002Injection.injectionId()).size());

        // 2. 筛查直接指出"杂质峰消失"
        Finding disappeared = sc.findings().stream()
                .filter(f -> f.code().equals(RuleCodes.IMPURITY_PEAK_DISAPPEARED))
                .findFirst().orElseThrow(() -> new AssertionError("未报告杂质峰消失"));
        assertEquals(Severity.CRITICAL, disappeared.severity());
        assertEquals("S-002", disappeared.sampleId());

        // 3. 证据里能读出"改前是多少、改后是多少"
        assertTrue(disappeared.evidence().get("impurityPercentBefore").startsWith("0.5"),
                "改前杂质应在 0.5%~0.6% 之间，实际: " + disappeared.evidence().get("impurityPercentBefore"));
        assertEquals("0.0", disappeared.evidence().get("impurityPercentAfter"));

        // 4. 同时报告"由不合格变合格"这一更强的信号
        assertTrue(sc.findings().stream()
                .anyMatch(f -> f.code().equals(RuleCodes.SPEC_CROSSING_BY_REINTEGRATION)));
    }

    @Test
    @DisplayName("'批次放行后才被发现' —— 放行前必须被门槛拦住")
    void batchCannotBeReleasedWhileFraudUnresolved() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReleaseGate gate = lab.gatekeeper.evaluate(s.batch.batchId(), lab.qa);

        assertFalse(gate.releasable(), "含未解释 CRITICAL 发现的批次不得放行");
        // 阻断原因必须是人能读懂的具体条件
        List<String> reasons = gate.failed().stream().map(ReleaseGate.Check::description).toList();
        assertTrue(reasons.stream().anyMatch(r -> r.contains("计划")), reasons.toString());
        assertTrue(reasons.stream().anyMatch(r -> r.contains("复核")), reasons.toString());
        assertTrue(reasons.stream().anyMatch(r -> r.contains("发现")), reasons.toString());
        assertThrows(IllegalStateException.class,
                () -> lab.gatekeeper.markReleased(s.batch.batchId(), lab.qa, gate),
                "即便有 QA 角色，门槛未过也不得放行");
    }

    @Test
    @DisplayName("'正常的重新进样和参数调整确实存在' —— 合法操作不应被误判为造假")
    void legitimateReintegrationWithReasonIsNotFlaggedAsFraud() {
        Lab lab = lab();
        var instr = lab.instrument("HPLC-33", "Waters", "ACQUITY", "SN-33");
        var spec = new org.qclab.cds.domain.Specification("P", "imp", 1.0,
                org.qclab.cds.domain.Specification.LimitType.MAX, "t");
        var batch = lab.batch("B-LEGIT", "P", spec, instr);
        lab.ingest.registerPlan(batch, List.of(
                new org.qclab.cds.domain.PlannedInjection("B-LEGIT#W-R1", "W", 1,
                        org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE)));

        var trace = org.qclab.cds.demo.SyntheticChromatogram.create().duration(14).interval(0.02).build(
                new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(4.2, 1.6, 0.05));
        var bytes = new org.qclab.cds.demo.VendorFileWriter()
                .injection(1, "B-LEGIT#W-R1", "W", 1,
                        org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE,
                        Instant.parse("2026-01-17T08:00:00Z"), trace).bytes();
        var sealed = lab.ingest.ingest(bytes, "HPLC-33", "B-LEGIT", lab.analyst.operatorId(), null);
        String id = sealed.get(0).injectionId();

        // 首版：方法里没设主峰保留时间 → 全部判为杂质，结果偏大
        var noMainRt = new org.qclab.cds.domain.IntegrationMethod("METHOD-STD", 1, "标准杂质方法",
                new java.util.TreeMap<>(java.util.Map.of(
                        org.qclab.cds.domain.IntegrationMethod.P_MIN_HEIGHT, 0.5,
                        org.qclab.cds.domain.IntegrationMethod.P_MIN_AREA, 0.5,
                        org.qclab.cds.domain.IntegrationMethod.P_SLOPE_SENSITIVITY, 0.001)),
                lab.qa.operatorId(), lab.clock.now());
        lab.integration.integrateFirstTime(id, noMainRt, lab.analyst.operatorId());

        // 有充分理由的正确重积分：补上主峰归属（这是方法修正，不是掩盖）
        lab.integration.reintegrate(id, lab.method(2, 6.0, 0.001), lab.analyst.operatorId(),
                "首版方法缺少主峰保留时间归属，按批准的 METHOD-STD v2 修正");

        ReviewScreening sc = lab.detector.screenBatch("B-LEGIT", lab.reviewer.operatorId());
        assertFalse(sc.findings().stream().anyMatch(f -> f.severity() == Severity.CRITICAL),
                "有正当理由的重新积分不应被标为 CRITICAL，实际: "
                        + sc.findings().stream().filter(f -> f.severity() == Severity.CRITICAL)
                        .map(Finding::code).toList());
        // 但重积分这件事本身仍被记录（MEDIUM/INFO 级别），复核人知情即可
        assertTrue(sc.findings().stream().anyMatch(f -> f.code().equals(RuleCodes.RAPID_REINTEGRATION)),
                "重新积分本身应当可见——只是不应被当成造假");
    }

    @Test
    @DisplayName("'复核人员时间有限不能逐条看' —— 正常数据的告警量必须远小于造假数据")
    void screeningSortsAttentionSoReviewerOnlySeesAFew() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());

        // 6 条封存进样里，造假集中在 S-001/S-002/S-003 三个样品
        long samplesWithBlockingFindings = sc.requiringAttention().stream()
                .map(Finding::sampleId).distinct().count();
        assertTrue(samplesWithBlockingFindings <= 4,
                "需人工介入的条目应集中在少数几个样品上，实际涉及 " + samplesWithBlockingFindings + " 个");

        // S-004 是正常样品，不应出现在必须先处理的列表里
        assertTrue(sc.requiringAttention().stream().noneMatch(f -> "S-004".equals(f.sampleId())),
                "正常样品不应进入必须先处理的列表");
    }
}
