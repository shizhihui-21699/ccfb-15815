package org.qclab.cds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qclab.cds.demo.FraudScenario;
import org.qclab.cds.demo.Lab;
import org.qclab.cds.demo.SyntheticChromatogram;
import org.qclab.cds.demo.VendorFileWriter;
import org.qclab.cds.domain.PlannedInjection;
import org.qclab.cds.domain.RawInjection;
import org.qclab.cds.service.IngestService;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 采集层抗篡改测试：针对"把不合格的那一针删掉重测"。
 */
class IngestIntegrityTest {

    @Test
    @DisplayName("删掉一针原始进样会同时留下计划缺口与序号跳号")
    void deletedInjectionIsVisibleAsPlanGapAndSequenceGap() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);

        IngestService.Reconciliation rec = s.reconciliation();
        assertEquals(java.util.List.of("BATCH-2026-001#S-001-R1"), rec.missingPlannedKeys(),
                "S-001 的第一针在计划里，但账本里没有，必须报告为缺失");
        assertEquals(java.util.List.of(3L), rec.sequenceGaps(),
                "仪器序号 3 在账本中不存在，必须报告为跳号");
        assertFalse(rec.balanced());
    }

    @Test
    @DisplayName("同一样品的重进样不能掩盖原进样的缺失")
    void repeatInjectionCannotMaskMissingOriginal() {
        // 这是最容易犯的建模错误：若重进样与首针共用 plannedKey，
        // "删首针、留重进样"在按 key 对账时看起来完全正常。
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);

        var s001Raw = s.lab.memory.rawsForBatch(s.batch.batchId()).stream()
                .filter(r -> "S-001".equals(r.sampleId())).toList();
        assertEquals(1, s001Raw.size(), "账本里只有重进样这一条 S-001 记录");
        assertEquals(2, s001Raw.get(0).runNumber());
        // 但计划缺口如实报告了第一针缺失
        assertTrue(s.reconciliation().missingPlannedKeys().contains("BATCH-2026-001#S-001-R1"));
    }

    @Test
    @DisplayName("同一进样 ID 重复入库被拒绝")
    void duplicateInjectionIdRejected() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);
        RawInjection existing = s.s002Injection;
        assertThrows(org.qclab.cds.store.InMemoryLabRepository.DuplicateRawException.class,
                () -> lab.memory.appendRaw(existing));
    }

    @Test
    @DisplayName("伪造 traceHash 的进样在构造时即被拒绝")
    void tamperedTraceHashRejectedAtConstruction() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        var trace = SyntheticChromatogram.create().duration(10).build(
                new SyntheticChromatogram.PeakSpec(5.0, 100.0, 0.05));
        assertThrows(IllegalArgumentException.class, () -> new RawInjection(
                "INJ-X", "HPLC-01", 99, "k", "S-X", 1, PlannedInjection.Purpose.SAMPLE,
                Instant.EPOCH, "u", RawInjection.RunStatus.COMPLETED, trace,
                "0".repeat(64), "n/a", "fmt", null),
                "traceHash 与信号不符时必须在构造阶段就拒绝，而不是等入库后才发现");
    }

    @Test
    @DisplayName("作废进样必须有原因，且原记录保留")
    void exclusionRequiresReasonAndKeepsRecord() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);

        assertThrows(IllegalArgumentException.class,
                () -> lab.ingest.exclude(s.s003Injection.injectionId(), "  ", lab.analyst.operatorId()),
                "空原因必须被拒绝");

        // 作废后原始记录依然可读，且状态派生为无效
        assertTrue(lab.memory.raw(s.s003Injection.injectionId()).isPresent());
        assertEquals(RawInjection.RunStatus.EXCLUDED_INVALID,
                lab.ingest.effectiveStatus(s.s003Injection.injectionId()));
    }

    @Test
    @DisplayName("采集前登记的每个计划项都在审计链上留痕")
    void planRegistrationIsAudited() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);
        long planRecords = lab.audit.all().stream()
                .filter(r -> "PLAN_REGISTERED".equals(r.action())).count();
        assertEquals(s.lab.repo.plansForBatch(s.batch.batchId()).size(), planRecords,
                "每个计划项都应有一条 PLAN_REGISTERED 审计记录");
    }

    @Test
    @DisplayName("同一份厂商文件重复采集会被序号唯一性拦下")
    void reingestingSameFileRejectedBySequenceUniqueness() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);
        var trace = SyntheticChromatogram.create().duration(10).build(
                new SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08));
        byte[] file = new VendorFileWriter()
                .injection(5, "BATCH-2026-001#S-002-R1", "S-002", 1,
                        PlannedInjection.Purpose.SAMPLE, Instant.parse("2026-01-15T09:50:00Z"), trace)
                .bytes();
        assertThrows(org.qclab.cds.store.InMemoryLabRepository.DuplicateRawException.class,
                () -> lab.ingest.ingest(file, s.instrument.instrumentId(), s.batch.batchId(),
                        lab.analyst.operatorId(), null),
                "序号 5 已被占用，重复导入同一文件必须失败");
    }
}
