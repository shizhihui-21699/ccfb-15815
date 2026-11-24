package org.qclab.cds;

import org.junit.jupiter.api.Test;
import org.qclab.cds.demo.SyntheticChromatogram;
import org.qclab.cds.domain.IntegrationMethod;
import org.qclab.cds.domain.IntegrationResult;
import org.qclab.cds.domain.Peak;
import org.qclab.cds.integration.PeakDetector;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** 先验：积分器能把主峰与杂质峰分开，且面积/百分比在预期量级。 */
class PeakDetectorSanityTest {

    private static IntegrationMethod method(double minHeight, double slope, double mainRt) {
        TreeMap<String, Double> p = new TreeMap<>();
        p.put(IntegrationMethod.P_SLOPE_SENSITIVITY, slope);
        p.put(IntegrationMethod.P_MIN_HEIGHT, minHeight);
        p.put(IntegrationMethod.P_MIN_AREA, 0.5);
        p.put(IntegrationMethod.P_MAIN_RT, mainRt);
        p.put(IntegrationMethod.P_MAIN_RT_WINDOW, 0.3);
        return new IntegrationMethod("M", 1, "test", p, "qa", java.time.Instant.EPOCH);
    }

    @Test
    void detectsMainPeakAndImpurity() {
        var trace = SyntheticChromatogram.create().duration(12).interval(0.005).build(
                new SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new SyntheticChromatogram.PeakSpec(4.0, 30, 0.06)
        );
        IntegrationResult r = new PeakDetector().integrate(trace, method(0.5, 0.0, 6.0));
        System.out.println("peaks=" + r.peaks().size() + " impurity%=" + r.impurityPercent());
        for (Peak p : r.peaks()) {
            System.out.printf("  rt=%.3f h=%.1f area=%.1f type=%s%n",
                    p.retentionTimeMin(), p.height(), p.area(), p.peakType());
        }
        assertTrue(r.peaks().size() >= 2, "应至少检出主峰与杂质峰");
        assertEquals(1, r.peaks().stream().filter(p -> Peak.MAIN.equals(p.peakType())).count(),
                "应恰有一个主峰");
        assertTrue(r.impurityPercent() > 0 && r.impurityPercent() < 20,
                "杂质百分比应在合理量级，实际=" + r.impurityPercent());
    }

    @Test
    void raisingThresholdHidesImpurity() {
        var trace = SyntheticChromatogram.create().duration(12).interval(0.005).build(
                new SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new SyntheticChromatogram.PeakSpec(4.0, 30, 0.06)
        );
        IntegrationResult strict = new PeakDetector().integrate(trace, method(0.5, 0.0, 6.0));
        IntegrationResult loose = new PeakDetector().integrate(trace, method(200.0, 3.0, 6.0));
        System.out.println("strict peaks=" + strict.peaks().size() + " loose peaks=" + loose.peaks().size());
        assertTrue(loose.peaks().size() < strict.peaks().size(), "抬高阈值应减少检出的峰");
    }
}
