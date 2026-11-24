package org.qclab.cds.service;

import org.qclab.cds.domain.IntegrationResult;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.domain.Peak;
import org.qclab.cds.crypto.Canonical;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 两个积分版本之间的峰差异。<b>不会因为新版本"更好看"就抹掉旧版本有、新版本没有的峰</b>——
 * 消失的峰被单独列出，交给复核人判断。
 *
 * <p>匹配规则：按保留时间就近匹配（窗口默认 0.15 min），匹配不上的旧峰记为"消失"，
 * 匹配不上的新峰记为"新增"。保留时间漂移过大时会同时产生"消失 + 新增"，这本身也值得注意。
 */
public record PeakDelta(
        String olderVersionId,
        String newerVersionId,
        List<Peak> disappeared,
        List<Peak> appeared,
        List<PeakChange> changed,
        double impurityPercentBefore,
        double impurityPercentAfter
) implements Canonical {

    private static final double MATCH_WINDOW_MIN = 0.15;

    public record PeakChange(Peak before, Peak after, double areaRatio, double rtShift) implements Canonical {
    }

    public PeakDelta {
        disappeared = List.copyOf(disappeared);
        appeared = List.copyOf(appeared);
        changed = List.copyOf(changed);
    }

    public static PeakDelta compute(IntegrationVersion older, IntegrationVersion newer) {
        return compute(older, newer, MATCH_WINDOW_MIN);
    }

    public static PeakDelta compute(IntegrationVersion older, IntegrationVersion newer, double windowMin) {
        IntegrationResult before = older.result();
        IntegrationResult after = newer.result();
        boolean[] matchedAfter = new boolean[after.peaks().size()];

        List<Peak> disappeared = new ArrayList<>();
        List<PeakChange> changed = new ArrayList<>();

        for (Peak b : before.peaks()) {
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < after.peaks().size(); i++) {
                if (matchedAfter[i]) {
                    continue;
                }
                Peak a = after.peaks().get(i);
                double d = Math.abs(a.retentionTimeMin() - b.retentionTimeMin());
                if (d <= windowMin && d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            if (best < 0) {
                disappeared.add(b);
            } else {
                matchedAfter[best] = true;
                Peak a = after.peaks().get(best);
                double ratio = b.area() == 0 ? Double.NaN : a.area() / b.area();
                if (Math.abs(ratio - 1.0) > 0.02 || !a.peakType().equals(b.peakType())) {
                    changed.add(new PeakChange(b, a, ratio, a.retentionTimeMin() - b.retentionTimeMin()));
                }
            }
        }

        List<Peak> appeared = new ArrayList<>();
        for (int i = 0; i < matchedAfter.length; i++) {
            if (!matchedAfter[i]) {
                appeared.add(after.peaks().get(i));
            }
        }

        return new PeakDelta(older.versionId(), newer.versionId(), disappeared, appeared, changed,
                before.impurityPercent(), after.impurityPercent());
    }

    /** 关键指标：杂质峰是否"凭空消失"。 */
    public boolean impurityDisappeared() {
        return disappeared.stream().anyMatch(Peak::isImpurity);
    }

    public TreeMap<String, String> evidence() {
        TreeMap<String, String> e = new TreeMap<>();
        e.put("olderVersionId", olderVersionId);
        e.put("newerVersionId", newerVersionId);
        e.put("disappearedPeaks", Integer.toString(disappeared.size()));
        e.put("appearedPeaks", Integer.toString(appeared.size()));
        e.put("changedPeaks", Integer.toString(changed.size()));
        e.put("impurityPercentBefore", org.qclab.cds.crypto.Canon.num(impurityPercentBefore));
        e.put("impurityPercentAfter", org.qclab.cds.crypto.Canon.num(impurityPercentAfter));
        for (int i = 0; i < disappeared.size(); i++) {
            Peak p = disappeared.get(i);
            e.put("disappeared." + i, "rt=" + p.retentionTimeMin() + " area=" + p.area() + " type=" + p.peakType());
        }
        return e;
    }
}
