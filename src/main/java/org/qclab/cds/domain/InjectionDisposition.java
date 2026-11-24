package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

import java.time.Instant;

/**
 * 对一次进样的处置声明（作废 / 恢复）。<b>不是修改原始记录</b>，而是一条新事实。
 *
 * <p>"作废一次不合格进样并重测"在法规上完全合法——只要留下原因、时间、人，
 * 并且复核人能看到"这一次被作废了"。本记录就是那个"留下"。
 */
public record InjectionDisposition(
        String dispositionId,
        String injectionId,
        Kind kind,
        String reason,
        String actor,
        Instant at
) implements Canonical {

    public enum Kind {
        /** 声明该进样无效，不进入结果计算。 */
        EXCLUDE,
        /** 撤销之前的作废（例如查明是仪器故障已修复、同意采用）。 */
        REINSTATE
    }

    public InjectionDisposition {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("处置必须写明原因");
        }
    }

    // canonicalForm() 使用 Canonical 的默认实现（Json.canonical），
    // 确保 contentHash() 与其它所有哈希路径使用同一种规范化表示。
}
