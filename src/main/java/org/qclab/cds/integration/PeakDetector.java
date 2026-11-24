package org.qclab.cds.integration;

import org.qclab.cds.domain.Chromatogram;
import org.qclab.cds.domain.IntegrationMethod;
import org.qclab.cds.domain.IntegrationResult;
import org.qclab.cds.domain.Peak;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性的峰检测器。
 *
 * <p>之所以自己实现而不是黑盒调用厂商算法，是因为监管的核心诉求是
 * <b>可复现</b>：给定相同的信号与相同的参数快照，任何人任何时候都必须得到
 * 逐点相同的峰表。厂商算法版本漂移恰恰是"同一个数据两次积分结果不同"
 * 却无法解释的常见根源。
 *
 * <p>算法分两步，与工业软件的语义一致：
 * <ol>
 *   <li><b>寻峰</b>：找出局部极大点，并用峰高阈值 {@code minHeight} 过滤。
 *       峰高阈值是"小峰是否被识别"的开关——抬高它，杂质峰就"消失"，
 *       这正是需要被审计追踪捕获的动作。</li>
 *   <li><b>定界</b>：从峰顶向两侧扩展，直到斜率回落到基线噪声以下
 *       （{@code slopeSensitivity}），或遇到与相邻峰之间的谷底。面积在
 *       基线以上用梯形积分计算。</li>
 * </ol>
 *
 * <p>注意：{@code slopeSensitivity} 只影响<b>峰的边界</b>，不影响"峰是否存在"。
 * 早期实现曾把它误用作峰值判据，结果峰顶附近导数恒接近 0，阈值一调高所有峰
 * 都被滤掉——这提醒我们，参数语义错误会让审计规则误报/漏报。
 */
public final class PeakDetector {

    public IntegrationResult integrate(Chromatogram trace, IntegrationMethod method) {
        double[] y = trace.intensities();
        int n = y.length;
        if (n < 3) {
            return IntegrationResult.of(List.of());
        }

        double slopeSensitivity = method.param(IntegrationMethod.P_SLOPE_SENSITIVITY, 0.0);
        double minHeight = method.param(IntegrationMethod.P_MIN_HEIGHT, 0.0);
        double minArea = method.param(IntegrationMethod.P_MIN_AREA, 0.0);
        double mainRt = method.param(IntegrationMethod.P_MAIN_RT, Double.NaN);
        double mainWindow = method.param(IntegrationMethod.P_MAIN_RT_WINDOW, 0.3);

        List<Integer> apexes = findApexes(y, minHeight);

        List<Peak> peaks = new ArrayList<>();
        for (int k = 0; k < apexes.size(); k++) {
            int apex = apexes.get(k);
            int prevApex = k == 0 ? -1 : apexes.get(k - 1);
            int nextApex = k == apexes.size() - 1 ? -1 : apexes.get(k + 1);

            int left = findLeftBoundary(y, apex, prevApex, slopeSensitivity, trace.intervalMin());
            int right = findRightBoundary(y, apex, nextApex, slopeSensitivity, trace.intervalMin());
            if (right <= left) {
                continue;
            }

            double area = trapezoid(y, left, right, trace.intervalMin());
            if (area < minArea) {
                continue;
            }
            double height = y[apex];
            double width = halfHeightWidth(y, apex, left, right);
            double rt = trace.timeAt(apex);
            // 峰归属：落在主峰保留时间窗口内的判为主成分，其余为杂质/未知。
            // 归属规则来自方法快照，因此同样可复现、可审计。
            String type = !Double.isNaN(mainRt) && Math.abs(rt - mainRt) <= mainWindow
                    ? Peak.MAIN : Peak.UNKNOWN;
            peaks.add(new Peak(
                    peaks.size(),
                    rt,
                    area,
                    height,
                    width,
                    trace.timeAt(left),
                    trace.timeAt(right),
                    type,
                    false
            ));
        }
        return IntegrationResult.of(peaks);
    }

    /** 局部极大点，且峰高达到阈值。平顶时取最高点中最左的一个。 */
    private static List<Integer> findApexes(double[] y, double minHeight) {
        List<Integer> apexes = new ArrayList<>();
        int i = 1;
        while (i < y.length - 1) {
            if (y[i] >= minHeight && y[i] > y[i - 1]) {
                // 向右走到不再上升为止，处理平顶
                int j = i;
                while (j + 1 < y.length && y[j + 1] == y[j]) {
                    j++;
                }
                boolean isPeak = j + 1 < y.length && y[j] > y[j + 1];
                if (isPeak) {
                    apexes.add(j);
                    i = j + 1;
                    continue;
                }
            }
            i++;
        }
        return apexes;
    }

    /**
     * 向左定界（坡脚语义）：峰顶附近斜率本就接近 0，因此不能"低于阈值就停"，
     * 否则峰宽会被压成零。正确做法是：先越过平顶的零斜率区，再沿上升沿一直走到
     * 斜率回落到阈值以下——那一点就是坡脚。
     *
     * @param slope 斜率灵敏度，单位为"信号量/分钟"
     */
    private static int findLeftBoundary(double[] y, int apex, int prevApex, double slope, double intervalMin) {
        int limit = prevApex < 0 ? 0 : (prevApex + apex) / 2;
        int i = apex;
        // 1) 越过峰顶附近的平缓区
        while (i > limit && derivativePerMin(y, i, intervalMin) <= slope) {
            i--;
        }
        // 2) 沿上升沿继续向左，直到斜率回落到阈值以下
        while (i > limit && derivativePerMin(y, i, intervalMin) > slope) {
            i--;
        }
        return i;
    }

    private static int findRightBoundary(double[] y, int apex, int nextApex, double slope, double intervalMin) {
        int limit = nextApex < 0 ? y.length - 1 : (apex + nextApex) / 2;
        int i = apex;
        while (i < limit && -derivativePerMin(y, i + 1, intervalMin) <= slope) {
            i++;
        }
        while (i < limit && -derivativePerMin(y, i + 1, intervalMin) > slope) {
            i++;
        }
        return i;
    }

    /** 后向差商，换算成"信号量/分钟"，使阈值与采样率无关。 */
    private static double derivativePerMin(double[] y, int i, double intervalMin) {
        if (i <= 0) {
            return 0;
        }
        return (y[i] - y[i - 1]) / intervalMin;
    }

    /** 基线以上面积，单位"信号量×分钟"；乘以采样间隔后与采样率无关。 */
    private static double trapezoid(double[] y, int from, int to, double intervalMin) {
        double sum = 0;
        double base = Math.min(y[from], y[to]);
        for (int i = from; i < to; i++) {
            double a = Math.max(0, y[i] - base);
            double b = Math.max(0, y[i + 1] - base);
            sum += (a + b) / 2.0;
        }
        return sum * intervalMin;
    }

    private static double halfHeightWidth(double[] y, int apex, int leftBound, int rightBound) {
        double half = y[apex] / 2.0;
        int left = apex;
        while (left > leftBound && y[left] > half) {
            left--;
        }
        int right = apex;
        while (right < rightBound && y[right] > half) {
            right++;
        }
        return Math.max(0, right - left);
    }
}
