package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

/**
 * 仪器登记。{@code adapterId} 指向厂商适配器——这是应对"厂商软件五花八门"的
 * 唯一接缝：适配器只负责把厂商导出物翻译成统一的原始进样，翻译之后的一切
 * （封存、积分、复核）对厂商无感知。
 */
public record Instrument(
        String instrumentId,
        String vendor,
        String model,
        String serial,
        String adapterId
) implements Canonical {
}
