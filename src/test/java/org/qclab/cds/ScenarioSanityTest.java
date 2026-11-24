package org.qclab.cds;

import org.junit.jupiter.api.Test;
import org.qclab.cds.demo.FraudScenario;
import org.qclab.cds.demo.Lab;
import org.qclab.cds.review.Finding;
import org.qclab.cds.review.ReviewScreening;
import org.qclab.cds.review.RuleCodes;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** 先验：演示场景本身能产生预期的数据形状（不涉及规则断言）。 */
class ScenarioSanityTest {

    @Test
    void scenarioShapes() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        FraudScenario s = FraudScenario.build(lab);

        System.out.println("sealed=" + s.sealed.size());
        s.sealed.forEach(r -> System.out.printf("  seq=%d key=%s purpose=%s%n",
                r.sequenceNo(), r.plannedKey(), r.purpose()));
        System.out.println("recon missing=" + s.reconciliation().missingPlannedKeys()
                + " gaps=" + s.reconciliation().sequenceGaps());
        System.out.println("s002 strict%=" + s.s002Strict.result().impurityPercent()
                + " relaxed%=" + s.s002Relaxed.result().impurityPercent());
        System.out.println("s002 strict peaks=" + s.s002Strict.result().peaks().size()
                + " relaxed peaks=" + s.s002Relaxed.result().peaks().size());

        ReviewScreening sc = lab.detector.screenBatch(s.batch.batchId(), lab.reviewer.operatorId());
        System.out.println("findings=" + sc.findings().size() + " blocking=" + sc.blocking());
        for (Finding f : sc.findings()) {
            System.out.printf("  [%s] %s %s sample=%s%n", f.severity(), f.code(), f.subjectId(), f.sampleId());
        }
        assertFalse(s.sealed.isEmpty());
    }
}
