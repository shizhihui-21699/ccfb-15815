package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

/**
 * 质量规格：判定结果是否合格的阈值。放在本系统内而不是人工记忆里，
 * 是为了让"结果是否越线"成为可被程序比对的事实——异常检测必须以它为基准，
 * 否则"把杂质峰改到限度以下"无从识别。
 */
public record Specification(
        String productId,
        String parameter,
        double limit,
        LimitType limitType,
        String source
) implements Canonical {

    public enum LimitType {
        /** 实测值不得高于限度。 */
        MAX,
        /** 实测值不得低于限度。 */
        MIN
    }

    public boolean isOutOfSpec(double measured) {
        return limitType == LimitType.MAX ? measured > limit : measured < limit;
    }

    /** 距限度的相对裕度：越小说明越贴线，越值得复核。 */
    public double marginRatio(double measured) {
        if (limit == 0) {
            return Math.abs(measured);
        }
        return Math.abs(limit - measured) / Math.abs(limit);
    }
}
