package org.qclab.cds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qclab.cds.demo.FraudScenario;
import org.qclab.cds.demo.Lab;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.service.PeakDelta;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** 积分层抗篡改测试：针对"改积分参数让杂质峰消失"。 */
class IntegrationIntegrityTest {

    @Test
    @DisplayName("重新积分只能追加新版本，不能覆盖旧版本")
    void reintegrationAppendsRatherThanOverwrites() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);

        var versions = lab.memory.versionsForInjection(s.s002Injection.injectionId());
        assertEquals(2, versions.size(), "两版积分必须都保留");
        assertEquals(1, versions.get(0).versionNo());
        assertEquals(2, versions.get(1).versionNo());
        assertEquals(versions.get(0).versionId(), versions.get(1).parentVersionId(),
                "v2 必须指向 v1，形成可追溯的版本链");
        assertNotEquals(versions.get(0).resultHash(), versions.get(1).resultHash());
    }

    @Test
    @DisplayName("首版不合格、重新积分后合格，两版并存的证据完整")
    void bothVersionsRemainInspectable() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);

        double strict = s.s002Strict.result().impurityPercent();
        double relaxed = s.s002Relaxed.result().impurityPercent();
        assertTrue(strict > FraudScenario.SPEC_LIMIT, "首版应超限，实际 " + strict);
        assertEquals(0.0, relaxed, 1e-9, "放宽阈值后杂质峰应消失");
    }

    @Test
    @DisplayName("版本对比列出消失的杂质峰，而非只呈现最终结果")
    void deltaExposesDisappearedImpurityPeak() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);

        PeakDelta delta = PeakDelta.compute(s.s002Strict, s.s002Relaxed);
        assertTrue(delta.impurityDisappeared(), "版本对比必须报告杂质峰消失");
        assertFalse(delta.disappeared().isEmpty());
        assertTrue(delta.impurityPercentBefore() > delta.impurityPercentAfter());
        // 证据里应有可核验的数值：严格版 2 峰（主峰+杂质），放宽版只剩主峰
        assertEquals("1", delta.evidence().get("disappearedPeaks"));
        assertEquals("0", delta.evidence().get("appearedPeaks"));
    }

    @Test
    @DisplayName("重新积分未写原因会被拒绝")
    void reintegrationRequiresReason() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);
        assertThrows(IllegalArgumentException.class,
                () -> lab.integration.reintegrate(s.s002Injection.injectionId(),
                        lab.relaxedMethod(2, 6.0), lab.analyst.operatorId(), "   "),
                "空白原因必须被拒绝");
    }

    @Test
    @DisplayName("未经批准的方法不能用于积分")
    void unapprovedMethodRejected() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);
        var bad = new org.qclab.cds.domain.IntegrationMethod("M-X", 1, "临时方法",
                new java.util.TreeMap<>(java.util.Map.of("minHeight", 99.0)), null, null);
        assertThrows(IllegalArgumentException.class,
                () -> lab.integration.reintegrate(s.s002Injection.injectionId(), bad,
                        lab.analyst.operatorId(), "临时试试"),
                "只接受已批准的方法版本");
    }

    @Test
    @DisplayName("已作废的进样不能再被积分")
    void excludedInjectionCannotBeIntegrated() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);
        assertThrows(IllegalStateException.class,
                () -> lab.integration.integrateFirstTime(s.s003Injection.injectionId(),
                        lab.method(1, 6.0, 0.001), lab.analyst.operatorId()));
    }

    @Test
    @DisplayName("积分方法的参数在版本里是快照，事后改方法库不影响历史结果")
    void methodParametersAreSnapshotted() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);

        IntegrationVersion v1 = s.s002Strict;
        // 原始方法对象与版本里的快照应是两个独立对象，但哈希一致
        var snapshot = v1.method();
        assertEquals(v1.methodHash(), snapshot.contentHash(),
                "版本内的方法快照必须自洽");
        assertNotSame(snapshot, lab.method(1, 6.0, 0.001));
        // 快照的参数确实记录了当时严格方法的阈值（minHeight=0.5），而不是放宽后的 20
        assertEquals(0.5, snapshot.param(org.qclab.cds.domain.IntegrationMethod.P_MIN_HEIGHT, -1), 1e-9);
        assertEquals(20.0, s.s002Relaxed.method()
                .param(org.qclab.cds.domain.IntegrationMethod.P_MIN_HEIGHT, -1), 1e-9);
    }

    @Test
    @DisplayName("同一进样不能有两条首版积分")
    void firstTimeIntegrationIsOnceOnly() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);
        assertThrows(IllegalStateException.class,
                () -> lab.integration.integrateFirstTime(s.s002Injection.injectionId(),
                        lab.method(1, 6.0, 0.001), lab.analyst.operatorId()),
                "已有首版时再次 integrateFirstTime 必须被拒绝，重新积分只能走 reintegrate");
    }
}
