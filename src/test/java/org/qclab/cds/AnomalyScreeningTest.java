package org.qclab.cds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qclab.cds.demo.FraudScenario;
import org.qclab.cds.demo.Lab;
import org.qclab.cds.review.Finding;
import org.qclab.cds.review.FindingDisposition;
import org.qclab.cds.review.ReleaseGate;
import org.qclab.cds.review.ReviewScreening;
import org.qclab.cds.review.RuleCodes;
import org.qclab.cds.review.Severity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异常筛查测试：针对"复核人员时间有限不能逐条看"。
 *
 * <p>要验证的不是"能发现异常"，而是<b>能把注意力排好序</b>：
 * 造假手法必须是 CRITICAL/HIGH，正常操作必须是 INFO 或缺席。
 */
class AnomalyScreeningTest {

    private static Lab lab() {
        return Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
    }

    @Test
    @DisplayName("三种造假手法都被排进必须先处理的那一档")
    void allThreeFraudsSurfaceAsBlocking() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());

        List<String> codes = sc.requiringAttention().stream().map(Finding::code).toList();
        assertTrue(codes.contains(RuleCodes.PLAN_GAP), "删针应触发计划缺口");
        assertTrue(codes.contains(RuleCodes.SEQUENCE_GAP), "删针应触发序号跳号");
        assertTrue(codes.contains(RuleCodes.IMPURITY_PEAK_DISAPPEARED), "改参数应触发杂质峰消失");
        assertTrue(codes.contains(RuleCodes.SPEC_CROSSING_BY_REINTEGRATION), "改参数应由不合格变合格");
        assertTrue(codes.contains(RuleCodes.EXCLUSION_AFTER_RESULT), "出结果后作废应被标出");
        assertTrue(sc.blocking());
    }

    @Test
    @DisplayName("关键发现携带可独立核验的证据，而不是黑箱分数")
    void criticalFindingsCarryEvidence() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());

        Finding disappeared = sc.findings().stream()
                .filter(f -> f.code().equals(RuleCodes.IMPURITY_PEAK_DISAPPEARED))
                .findFirst().orElseThrow();

        // 证据必须让复核人自己能重算一遍
        assertTrue(disappeared.evidence().containsKey("olderVersionId"));
        assertTrue(disappeared.evidence().containsKey("newerVersionId"));
        assertTrue(disappeared.evidence().containsKey("impurityPercentBefore"));
        assertTrue(disappeared.evidence().containsKey("impurityPercentAfter"));
        assertTrue(disappeared.evidence().containsKey("disappeared.0"),
                "应列出具体消失的峰（保留时间/面积/类型）");
        assertFalse(disappeared.suggestedActions().isEmpty(), "应给出可执行的下一步");
    }

    @Test
    @DisplayName("正常样品 S-004 不产生任何 CRITICAL 或 HIGH 发现")
    void cleanSampleProducesNoBlockingFindings() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());

        List<Finding> s004 = sc.findings().stream()
                .filter(f -> "S-004".equals(f.sampleId())).toList();
        assertTrue(s004.stream().noneMatch(f -> f.severity() == Severity.CRITICAL
                        || f.severity() == Severity.HIGH),
                "正常样品不应被淹没在告警里，实际: " + s004.stream().map(Finding::code).toList());
    }

    @Test
    @DisplayName("筛查按严重度排序，复核人从上往下看即可")
    void findingsAreOrderedBySeverity() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());

        int last = -1;
        for (Finding f : sc.findings()) {
            assertTrue(f.severity().ordinal() >= last, "发现必须按严重度升序排列");
            last = f.severity().ordinal();
        }
        // 顶部必须是 CRITICAL
        assertEquals(Severity.CRITICAL, sc.findings().get(0).severity());
    }

    @Test
    @DisplayName("作废针不再产生结果类告警，只报'作废'本身")
    void excludedInjectionDoesNotProduceResultFindings() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());

        // S-003 原始针 0.62% 超限，但已作废；不应出现指向它的 OUT_OF_SPEC
        List<Finding> outOfSpecOnS003 = sc.findings().stream()
                .filter(f -> f.code().equals(RuleCodes.OUT_OF_SPEC))
                .filter(f -> "S-003".equals(f.sampleId()))
                .toList();
        assertTrue(outOfSpecOnS003.isEmpty(),
                "已作废的针不应再拿数值比限度，否则制造误导性噪声");
    }

    @Test
    @DisplayName("筛查报告只包含被标出的少数条目，正常流程不产生阻断")
    void screeningIsQuietOnCleanLab() {
        Lab lab = lab();
        var instr = lab.instrument("HPLC-09", "Waters", "Arc", "SN-9");
        var spec = new org.qclab.cds.domain.Specification("P", "imp", 1.0,
                org.qclab.cds.domain.Specification.LimitType.MAX, "test");
        var batch = lab.batch("B-CLEAN", "P", spec, instr);

        lab.ingest.registerPlan(batch, List.of(
                new org.qclab.cds.domain.PlannedInjection("B-CLEAN#X-R1", "X", 1,
                        org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE)));
        var trace = org.qclab.cds.demo.SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(4.2, 1.6, 0.05));
        var bytes = new org.qclab.cds.demo.VendorFileWriter()
                .injection(1, "B-CLEAN#X-R1", "X", 1,
                        org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE,
                        Instant.parse("2026-01-16T08:00:00Z"), trace).bytes();
        var sealed = lab.ingest.ingest(bytes, "HPLC-09", "B-CLEAN", lab.analyst.operatorId(), null);
        lab.integration.integrateFirstTime(sealed.get(0).injectionId(),
                lab.method(1, 6.0, 0.001), lab.analyst.operatorId());

        ReviewScreening sc = lab.detector.screenBatch("B-CLEAN", lab.reviewer.operatorId());
        assertFalse(sc.blocking(), "干净批次不应有阻断项，实际: "
                + sc.requiringAttention().stream().map(Finding::code).toList());
        assertTrue(sc.findings().size() <= 1, "干净批次应有很少的发现，实际 " + sc.findings().size());
    }

    @Test
    @DisplayName("复核人可以接受一条发现并说明理由，发现本身不被删除")
    void reviewerCanAcceptFindingWithRationaleButFindingPersists() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());

        Finding target = sc.findings().stream()
                .filter(f -> f.code().equals(RuleCodes.RAPID_REINTEGRATION))
                .findFirst().orElseThrow();

        lab.detector.recordDisposition(target, FindingDisposition.Decision.ACCEPTED,
                lab.reviewer, "两次调参间隔短是因为第一次方法参数录入错误，已核对方法版本记录");

        var disps = lab.memory.explanationsFor(FindingDisposition.keyOf(target));
        assertEquals(1, disps.size());
        assertEquals(FindingDisposition.Decision.ACCEPTED, disps.get(0).decision());

        // 发现本身仍然在筛查结果里
        ReviewScreening again = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());
        assertTrue(again.findings().stream().anyMatch(f -> f.code().equals(target.code())
                && f.subjectId().equals(target.subjectId())), "接受不等于删除");
    }

    @Test
    @DisplayName("说明理由必须填写，空理由被拒绝")
    void dispositionRequiresRationale() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());
        Finding f = sc.findings().get(0);
        assertThrows(IllegalArgumentException.class,
                () -> lab.detector.recordDisposition(f, FindingDisposition.Decision.ACCEPTED,
                        lab.reviewer, "  "));
    }

    @Test
    @DisplayName("分析员不能处置发现（只有复核权限者可以）")
    void analystCannotDisposeFindings() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());
        Finding f = sc.findings().get(0);
        assertThrows(IllegalStateException.class,
                () -> lab.detector.recordDisposition(f, FindingDisposition.Decision.ACCEPTED,
                        lab.analyst, "我自己觉得没问题"));
    }

    @Test
    @DisplayName("先改参数让峰消失、再作废该针，不能因此漏报版本链问题")
    void excludingInjectionCannotHideReintegrationHistory() {
        Lab lab = lab();
        var instr = lab.instrument("HPLC-44", "Agilent", "1260", "SN-44");
        var spec = new org.qclab.cds.domain.Specification("P", "imp", 0.50,
                org.qclab.cds.domain.Specification.LimitType.MAX, "t");
        var batch = lab.batch("B-HIDE", "P", spec, instr);
        lab.ingest.registerPlan(batch, List.of(
                new org.qclab.cds.domain.PlannedInjection("B-HIDE#Q-R1", "Q", 1,
                        org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE)));
        var trace = org.qclab.cds.demo.SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(4.2, 7.6, 0.06));
        var bytes = new org.qclab.cds.demo.VendorFileWriter()
                .injection(1, "B-HIDE#Q-R1", "Q", 1,
                        org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE,
                        Instant.parse("2026-01-18T08:00:00Z"), trace).bytes();
        var sealed = lab.ingest.ingest(bytes, "HPLC-44", "B-HIDE", lab.analyst.operatorId(), null);
        String id = sealed.get(0).injectionId();

        // 手法 B：改参数让杂质峰消失
        lab.integration.integrateFirstTime(id, lab.method(1, 6.0, 0.001), lab.analyst.operatorId());
        lab.integration.reintegrate(id, lab.relaxedMethod(2, 6.0), lab.analyst.operatorId(),
                "调整基线");
        // 再叠加一层：把这一针作废，试图让整条路径"不存在"
        lab.ingest.exclude(id, "系统适用性失败", lab.analyst.operatorId());

        ReviewScreening sc = lab.detector.screenBatch("B-HIDE", lab.reviewer.operatorId());
        assertTrue(sc.findings().stream().anyMatch(f -> f.code().equals(RuleCodes.IMPURITY_PEAK_DISAPPEARED)
                        && f.severity() == Severity.CRITICAL),
                "作废不能掩盖此前发生的'改参数让峰消失'，实际: "
                        + sc.findings().stream().map(f -> f.code() + "/" + f.severity()).toList());
        // 同时"先有结果后作废"也应报告
        assertTrue(sc.findings().stream().anyMatch(f -> f.code().equals(RuleCodes.EXCLUSION_AFTER_RESULT)));
    }

    @Test
    @DisplayName("统计视图给出按严重度与按规则的计数，供管理层看趋势")
    void screeningProvidesAggregates() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());
        assertTrue(sc.countsBySeverity().containsKey("CRITICAL"));
        assertTrue(sc.countsByCode().getOrDefault(RuleCodes.SEQUENCE_GAP, 0L) >= 1);
        // 计数与明细一致
        long criticalDetail = sc.findings().stream()
                .filter(f -> f.severity() == Severity.CRITICAL).count();
        assertEquals(criticalDetail, sc.countsBySeverity().getOrDefault("CRITICAL", 0L));
    }
}
