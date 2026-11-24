package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

/**
 * 序列计划中的一针。计划在采数<b>之前</b>登记，这是发现"偷偷删掉不合格那一针"
 * 的关键：计划里有、账本里既没有结果也没有"未执行"说明，就是缺口。
 */
public record PlannedInjection(
        String plannedKey,
        String sampleId,
        int runNumber,
        Purpose purpose
) implements Canonical {

    public enum Purpose {
        /** 系统适用性/空白/标准品等，不进入样品结果。 */
        SYSTEM_SUITABILITY,
        BLANK,
        STANDARD,
        /** 样品进样。 */
        SAMPLE,
        /** 复核性重进样，必须带原因。 */
        REPEAT
    }
}
