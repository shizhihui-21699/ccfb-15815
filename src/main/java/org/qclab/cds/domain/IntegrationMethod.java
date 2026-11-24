package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

import java.time.Instant;
import java.util.TreeMap;

/**
 * 积分方法（含其参数）。方法本身也有版本与批准人，
 * 且参数在积分时<b>快照</b>进结果，不引用"当前方法"——
 * 否则事后改方法会静默改变历史结果的解释。
 */
public record IntegrationMethod(
        String methodId,
        int methodVersion,
        String name,
        TreeMap<String, Double> parameters,
        String approvedBy,
        Instant approvedAt
) implements Canonical {

    public static final String P_SLOPE_SENSITIVITY = "slopeSensitivity";
    public static final String P_PEAK_WIDTH = "peakWidth";
    public static final String P_BASELINE_MODEL = "baselineModel";
    public static final String P_MIN_AREA = "minArea";
    public static final String P_MIN_HEIGHT = "minHeight";
    public static final String P_SHOULDER_DETECTION = "shoulderDetection";
    /** 主成分峰的预期保留时间；落在窗口内即判为主峰。 */
    public static final String P_MAIN_RT = "mainRt";
    /** 主峰归属窗口（分钟，双侧）。 */
    public static final String P_MAIN_RT_WINDOW = "mainRtWindow";

    public IntegrationMethod {
        parameters = new TreeMap<>(parameters);
        if (methodVersion < 1) {
            throw new IllegalArgumentException("方法版本从 1 开始");
        }
    }

    /**
     * 刻意<b>不</b>覆写 {@code canonicalForm()}：方法哈希必须与
     * {@code Json.canonical(method)} 逐字节一致，因为积分服务正是用后者计算并存入
     * {@code methodHash}。若这里另写一套 Canon.kv 表示，就会出现
     * "哈希说 A、内容说 B"的脱钩，等于给篡改留了口子。
     * {@link Canonical} 的默认实现即使用 Json，正是为此。
     */

    public double param(String key, double fallback) {
        Double v = parameters.get(key);
        return v == null ? fallback : v;
    }
}
