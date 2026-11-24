package org.qclab.cds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qclab.cds.demo.FraudScenario;
import org.qclab.cds.demo.Lab;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.domain.ReviewSignature;
import org.qclab.cds.service.ReviewService;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复核签名测试：针对"复核之后偷改数据"与"自己复核自己"。
 */
class ReviewSignatureTest {

    private static Lab lab() {
        return Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
    }

    @Test
    @DisplayName("复核签名绑定结果+方法+原始数据，验签通过")
    void validSignatureVerifies() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);

        ReviewSignature sig = lab.reviews.sign(s.s002Strict.versionId(), lab.reviewer, "首版积分与原始信号一致");
        assertEquals(ReviewSignature.Status.VALID, sig.status());
        assertTrue(lab.reviews.verify(sig).valid(), lab.reviews.verify(sig).reason());
    }

    @Test
    @DisplayName("复核后重新积分：旧签名被标记为已取代，密码学上仍有效但不再代表当前结果")
    void reintegrationSupersedesRatherThanInvalidates() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);

        // 用 S-004（场景中只有一版）先复核，再看它的签名对当前版本有效
        IntegrationVersion v1 = lab.memory.latestVersion(s.s004Injection.injectionId()).orElseThrow();
        ReviewSignature sig = lab.reviews.sign(v1.versionId(), lab.reviewer, "v1 数据一致");
        assertTrue(lab.reviews.verify(sig).valid());
        assertTrue(lab.reviews.isEffectiveForCurrentVersion(sig),
                "S-004 只有一版，签名应立即对当前版本生效");

        // 分析员对这个进样又出一版
        IntegrationVersion v2 = lab.integration.reintegrate(s.s004Injection.injectionId(),
                lab.relaxedMethod(3, 6.0), lab.analyst.operatorId(), "基线漂移，重新积分");

        // 链上状态变为 SUPERSEDED，而不是 INVALIDATED——这是正常的重新积分，不是篡改
        assertEquals(ReviewSignature.Status.SUPERSEDED, lab.reviews.effectiveStatus(sig));
        // 密码学上依然有效：它确实签的是那一版结果
        assertTrue(lab.reviews.verify(sig).valid(),
                "被取代的签名在密码学上仍有效，混淆这一点会让审计无法区分正常调参与篡改");
        // 但不再代表当前结果
        assertFalse(lab.reviews.isEffectiveForCurrentVersion(sig),
                "v2 已成为当前版本，v1 的复核不再有效");
        assertEquals(v2.versionId(), lab.memory.latestVersion(s.s004Injection.injectionId())
                .orElseThrow().versionId());
    }

    @Test
    @DisplayName("签名不能挪用到另一条记录上")
    void signatureCannotBeTransplanted() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewSignature sigA = lab.reviews.sign(s.s002Strict.versionId(), lab.reviewer, "A");

        // 试图把 A 的签名基串安到 B 上
        IntegrationVersion other = s.s003Injection != null
                ? lab.memory.latestVersion(s.s003Injection.injectionId()).orElseThrow()
                : null;
        assertNotNull(other);
        ReviewSignature transplanted = new ReviewSignature("SIG-FAKE", other.versionId(),
                sigA.reviewerId(), sigA.reviewerRole(), ReviewSignature.Status.VALID,
                sigA.boundPayloadHash(), sigA.signatureB64(), sigA.signedAt(), "A", null, null);
        assertFalse(lab.reviews.verify(transplanted).valid(),
                "绑定载荷含 versionId，挪用到别的版本必然验签失败");
    }

    @Test
    @DisplayName("分析员不得复核自己的结果")
    void analystCannotReviewOwnWork() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        assertThrows(IllegalStateException.class,
                () -> lab.reviews.sign(s.s002Strict.versionId(), lab.analyst, "我自己看过了"),
                "同一个人既做又查等于没查");
    }

    @Test
    @DisplayName("复核必须留下意见")
    void reviewRequiresComment() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        assertThrows(IllegalArgumentException.class,
                () -> lab.reviews.sign(s.s002Strict.versionId(), lab.reviewer, " "));
    }

    @Test
    @DisplayName("直改签名表的 status 列无法让被取代的签名复活：状态以审计链为准")
    void tamperedStatusColumnCannotResurrectSupersededSignature() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewSignature sig = lab.reviews.sign(s.s002Strict.versionId(), lab.reviewer, "v1");
        lab.integration.reintegrate(s.s002Injection.injectionId(),
                lab.relaxedMethod(3, 6.0), lab.analyst.operatorId(), "调整");

        // 模拟 DBA 直接改内存仓库里该签名的 status 列为 VALID
        forceStatusColumn(lab, sig.signatureId(), ReviewSignature.Status.VALID);

        // 即使列表里的 status 显示 VALID，链上重放的结果仍是 SUPERSEDED
        assertEquals(ReviewSignature.Status.SUPERSEDED, lab.reviews.effectiveStatus(sig),
                "状态必须以审计链为准——那是 append-only 且逐条哈希串联的");
        assertFalse(lab.reviews.isEffectiveForCurrentVersion(sig));
    }

    /** 测试辅助：绕过应用层改签名表里的 status 列。 */
    private static void forceStatusColumn(Lab lab, String signatureId, ReviewSignature.Status status) {
        try {
            var f = org.qclab.cds.store.InMemoryLabRepository.class.getDeclaredField("signatures");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, java.util.List<ReviewSignature>>) f.get(lab.memory);
            for (var entry : map.entrySet()) {
                var list = entry.getValue();
                for (int i = 0; i < list.size(); i++) {
                    ReviewSignature r = list.get(i);
                    if (r.signatureId().equals(signatureId)) {
                        list.set(i, new ReviewSignature(r.signatureId(), r.versionId(), r.reviewerId(),
                                r.reviewerRole(), status, r.boundPayloadHash(), r.signatureB64(),
                                r.signedAt(), r.comment(), r.invalidatedAt(), r.invalidationReason()));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("改写原始信号内容会让已签名结果的验签失败")
    void alteringRawSignalBreaksSignature() {
        Lab lab = lab();
        FraudScenario s = FraudScenario.build(lab);
        ReviewSignature sig = lab.reviews.sign(s.s002Strict.versionId(), lab.reviewer, "ok");

        // 模拟有人替换了原始信号（通过反射改内存仓库中的进样）
        RawSwap.swapTrace(lab, s.s002Injection.injectionId(), s.s003Injection);

        assertFalse(lab.reviews.verify(sig).valid(),
                "原始数据的哈希参与绑定，替换信号必须让签名失效");
    }

    /** 测试辅助：把一个进样的信号替换成另一个（绕过正常 API，模拟直改数据库）。 */
    private static final class RawSwap {
        static void swapTrace(Lab lab, String targetInjectionId, org.qclab.cds.domain.RawInjection donor) {
            try {
                var f = org.qclab.cds.store.InMemoryLabRepository.class.getDeclaredField("raws");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                var map = (java.util.Map<String, org.qclab.cds.domain.RawInjection>) f.get(lab.memory);
                var original = map.get(targetInjectionId);
                // 保留 ID 与计划归属，只把信号换成捐赠者的（哈希也随之改变）
                map.put(targetInjectionId, new org.qclab.cds.domain.RawInjection(
                        original.injectionId(), original.instrumentId(), original.sequenceNo(),
                        original.plannedKey(), original.sampleId(), original.runNumber(),
                        original.purpose(), original.acquiredAt(), original.acquiredBy(),
                        original.status(), donor.trace(), donor.traceHash(), donor.rawPayloadHash(),
                        donor.vendorFileFormat(), original.note()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
