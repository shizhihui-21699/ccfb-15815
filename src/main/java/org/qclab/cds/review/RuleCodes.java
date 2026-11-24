package org.qclab.cds.review;

/**
 * 规则代码。稳定、可统计、可审计；复核记录里引用的是这些代码，
 * 不依赖人类可读的标题，因此改文案不会破坏历史统计。
 */
public final class RuleCodes {

    private RuleCodes() {
    }

    /** 计划里有、账本里没有的进样（被删/被藏）。 */
    public static final String PLAN_GAP = "PLAN_GAP";
    /** 仪器序号跳号，说明仪器侧发生了本系统未见的事件。 */
    public static final String SEQUENCE_GAP = "SEQUENCE_GAP";
    /** 同一进样积分版本数异常偏多。 */
    public static final String REPEATED_REINTEGRATION = "REPEATED_REINTEGRATION";
    /** 重新积分使杂质峰消失。 */
    public static final String IMPURITY_PEAK_DISAPPEARED = "IMPURITY_PEAK_DISAPPEARED";
    /** 重新积分使结果从不合格"穿越"到合格。 */
    public static final String SPEC_CROSSING_BY_REINTEGRATION = "SPEC_CROSSING_BY_REINTEGRATION";
    /** 当前版本结果超出规格限度。 */
    public static final String OUT_OF_SPEC = "OUT_OF_SPEC";
    /** 峰值被手工增删/拖拽。 */
    public static final String MANUAL_PEAK_EDIT = "MANUAL_PEAK_EDIT";
    /** 有进样被声明作废。 */
    public static final String INJECTION_EXCLUDED = "INJECTION_EXCLUDED";
    /** 重进样但未写明原因。 */
    public static final String REPEAT_WITHOUT_REASON = "REPEAT_WITHOUT_REASON";
    /** 短时间内的连续重积分。 */
    public static final String RAPID_REINTEGRATION = "RAPID_REINTEGRATION";
    /** 复核签名已失效（复核后记录被改动）。 */
    public static final String REVIEW_INVALIDATED = "REVIEW_INVALIDATED";
    /** 历史版本的复核签名因出现新版本而被取代（正常流程，仅提示）。 */
    public static final String REVIEW_SUPERSEDED = "REVIEW_SUPERSEDED";
    /** 待放行的最新版本尚无有效复核签名。 */
    public static final String UNREVIEWED_VERSION = "UNREVIEWED_VERSION";
    /** 审计链断裂。 */
    public static final String AUDIT_CHAIN_BROKEN = "AUDIT_CHAIN_BROKEN";
    /** 结果极其贴近限度（贴线），值得复核人确认不是"调参调出来的"。 */
    public static final String NEAR_LIMIT = "NEAR_LIMIT";
    /** 作废发生在结果记录之后（先看到不合格再声明作废）。 */
    public static final String EXCLUSION_AFTER_RESULT = "EXCLUSION_AFTER_RESULT";
}
