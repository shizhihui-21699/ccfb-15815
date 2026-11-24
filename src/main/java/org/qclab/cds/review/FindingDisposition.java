package org.qclab.cds.review;

import org.qclab.cds.crypto.Canonical;

import java.time.Instant;

/**
 * 复核人对一条异常的处置意见。
 *
 * <p>这是"系统负责排序、人负责判断"这个分工的落点。复核人看到
 * {@link RuleCodes#IMPURITY_PEAK_DISAPPEARED} 时可以：
 * <ul>
 *   <li><b>ACCEPTED</b>——查明是基线漂移导致误判，接受当前结果并说明理由；</li>
 *   <li><b>REJECTED</b>——认定该结果不可用，要求重新积分/重进样；</li>
 *   <li><b>ESCALATED</b>——超出自己判断权限，升级给 QA。</li>
 * </ul>
 *
 * <p>关键约束：<b>"接受"不等于"删除"</b>。异常仍留在筛查结果里，
 * 只是多了一条"谁在何时以何理由接受了它"的记录。放行门槛据此判断
 * 该发现是否"已解释"，而解释本身不可篡改。
 *
 * @param findingKey 发现的稳定标识（规则代码 + 主体 ID），用于与筛查结果对应
 */
public record FindingDisposition(
        String dispositionId,
        String findingKey,
        String ruleCode,
        String subjectId,
        Decision decision,
        String reviewerId,
        String rationale,
        Instant at
) implements Canonical {

    public enum Decision {
        ACCEPTED,
        REJECTED,
        ESCALATED
    }

    public FindingDisposition {
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("处置异常必须写明理由");
        }
    }

    /** 与 {@link Finding} 对应的稳定键。 */
    public static String keyOf(Finding f) {
        return f.code() + "|" + f.subjectId();
    }

    // canonicalForm() 使用 Canonical 的默认实现（Json.canonical），
    // 确保 contentHash() 与其它所有哈希路径使用同一种规范化表示。
}
