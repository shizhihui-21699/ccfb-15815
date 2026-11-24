package org.qclab.cds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qclab.cds.demo.Lab;
import org.qclab.cds.domain.ReviewSignature;
import org.qclab.cds.review.ReviewScreening;
import org.qclab.cds.review.RuleCodes;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "失效看护"是否真的接上了。
 *
 * <p>这类机制最容易变成纸面功夫：写了一个 {@code sweepInvalidations()}，
 * 注释里说"打开复核界面时运行"，但没有任何地方调用它。
 * 本测试专门验证每条失效路径都被真实触发。
 */
class HousekeepingWiringTest {

    private static Lab lab() {
        return Lab.createDeterministic(Instant.parse("2026-01-16T08:00:00Z"));
    }

    /** 构造一个已复核、只有一版结果的干净进样。 */
    private record Fixture(Lab lab, String injectionId, String versionId, ReviewSignature signature) {
    }

    private static Fixture reviewedCleanSample() {
        Lab lab = lab();
        var instr = lab.instrument("HPLC-55", "Agilent", "1290", "SN-55");
        var spec = new org.qclab.cds.domain.Specification("P", "imp", 1.0,
                org.qclab.cds.domain.Specification.LimitType.MAX, "t");
        var batch = lab.batch("B-HK", "P", spec, instr);
        lab.ingest.registerPlan(batch, java.util.List.of(
                new org.qclab.cds.domain.PlannedInjection("B-HK#Z-R1", "Z", 1,
                        org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE)));
        var trace = org.qclab.cds.demo.SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(4.2, 1.6, 0.05));
        var bytes = new org.qclab.cds.demo.VendorFileWriter()
                .injection(1, "B-HK#Z-R1", "Z", 1,
                        org.qclab.cds.domain.PlannedInjection.Purpose.SAMPLE,
                        Instant.parse("2026-01-16T08:00:00Z"), trace).bytes();
        var sealed = lab.ingest.ingest(bytes, "HPLC-55", "B-HK", lab.analyst.operatorId(), null);
        var v = lab.integration.integrateFirstTime(sealed.get(0).injectionId(),
                lab.method(1, 6.0, 0.001), lab.analyst.operatorId());
        var sig = lab.reviews.sign(v.versionId(), lab.reviewer, "一致");
        assertTrue(lab.reviews.isEffectiveForCurrentVersion(sig));
        return new Fixture(lab, sealed.get(0).injectionId(), v.versionId(), sig);
    }

    @Test
    @DisplayName("作废进样会真实触发其复核签名失效")
    void exclusionActuallyInvalidatesSignature() {
        Fixture f = reviewedCleanSample();
        f.lab.ingest.exclude(f.injectionId(), "基线严重漂移，峰形不可用", f.lab.analyst.operatorId());

        assertEquals(ReviewSignature.Status.INVALIDATED,
                f.lab.reviews.effectiveStatus(f.signature()));
        assertFalse(f.lab.reviews.isEffectiveForCurrentVersion(f.signature()));
    }

    @Test
    @DisplayName("撤销作废后原签名不会自动复活，仍需重新复核")
    void reinstatementDoesNotResurrectOldSignature() {
        Fixture f = reviewedCleanSample();
        f.lab.ingest.exclude(f.injectionId(), "疑似气泡", f.lab.analyst.operatorId());
        f.lab.ingest.reinstate(f.injectionId(), "查明确为气泡，但不影响该针数据", f.lab.analyst.operatorId());

        assertFalse(f.lab.reviews.isEffectiveForCurrentVersion(f.signature()),
                "作废状态变化后，旧复核意见不能自动复活");
    }

    @Test
    @DisplayName("复核后篡改原始信号，筛查扫描会把签名标记为失效并报 CRITICAL")
    void tamperingIsCaughtByScreeningSweep() {
        Fixture f = reviewedCleanSample();
        // 绕过应用层替换原始信号
        swapTrace(f.lab(), f.injectionId());

        ReviewScreening sc = f.lab().detector.screenBatch("B-HK", f.lab().reviewer.operatorId());
        boolean caught = sc.findings().stream()
                .anyMatch(x -> x.code().equals(RuleCodes.REVIEW_INVALIDATED)
                        && x.severity() == org.qclab.cds.review.Severity.CRITICAL);
        assertTrue(caught, "复核后篡改必须被扫描捕获，实际发现: "
                + sc.findings().stream().map(org.qclab.cds.review.Finding::code).toList());
        assertEquals(ReviewSignature.Status.INVALIDATED, f.lab().reviews.effectiveStatus(f.signature()));
    }

    @SuppressWarnings("unchecked")
    private static void swapTrace(Lab lab, String injectionId) {
        try {
            var field = org.qclab.cds.store.InMemoryLabRepository.class.getDeclaredField("raws");
            field.setAccessible(true);
            var map = (java.util.Map<String, org.qclab.cds.domain.RawInjection>) field.get(lab.memory);
            var original = map.get(injectionId);
            var tampered = org.qclab.cds.demo.SyntheticChromatogram.create()
                    .duration(14).interval(0.005).build(
                            new org.qclab.cds.demo.SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08));
            map.put(injectionId, new org.qclab.cds.domain.RawInjection(
                    original.injectionId(), original.instrumentId(), original.sequenceNo(),
                    original.plannedKey(), original.sampleId(), original.runNumber(),
                    original.purpose(), original.acquiredAt(), original.acquiredBy(),
                    original.status(), tampered, tampered.contentHash(), original.rawPayloadHash(),
                    original.vendorFileFormat(), original.note()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
