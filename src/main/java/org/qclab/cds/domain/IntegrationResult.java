package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

import java.util.ArrayList;
import java.util.List;

/**
 * 积分结果：某一版积分的产物。
 *
 * <p>与厂商文件导出的"结果"不同，这里的结果总是挂在
 * {@link IntegrationVersion} 上，因此"这个峰的数目是怎么来的"永远可回溯。
 */
public record IntegrationResult(
        List<Peak> peaks,
        double mainPeakArea,
        double impurityTotalArea,
        double impurityPercent
) implements Canonical {

    public IntegrationResult {
        peaks = List.copyOf(peaks);
    }

    public static IntegrationResult of(List<Peak> peaks) {
        double main = 0;
        double impurity = 0;
        for (Peak p : peaks) {
            if (Peak.MAIN.equals(p.peakType())) {
                main += p.area();
            } else if (p.isImpurity()) {
                impurity += p.area();
            }
        }
        double total = main + impurity;
        double pct = total <= 0 ? 0.0 : impurity / total * 100.0;
        return new IntegrationResult(peaks, main, impurity, pct);
    }

    public long impurityPeakCount() {
        return peaks.stream().filter(Peak::isImpurity).count();
    }

    public List<Peak> manuallyEditedPeaks() {
        List<Peak> out = new ArrayList<>();
        for (Peak p : peaks) {
            if (p.manuallyEdited()) {
                out.add(p);
            }
        }
        return out;
    }
}
