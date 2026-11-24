package org.qclab.cds.review;

/**
 * 异常等级。复核队列按此排序，复核人先看 CRITICAL。
 */
public enum Severity {
    /** 明确的可疑操作或数据完整性破坏，必须解释后才能放行。 */
    CRITICAL,
    /** 需要说明的高风险模式（例如短时间反复重积分导致杂质峰消失）。 */
    HIGH,
    /** 值得看一眼（例如手工编辑峰），有合理原因可一键通过。 */
    MEDIUM,
    /** 仅提示，不阻塞。 */
    INFO
}
