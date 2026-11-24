package org.qclab.cds.ingest;

import org.qclab.cds.domain.Chromatogram;
import org.qclab.cds.domain.PlannedInjection;
import org.qclab.cds.domain.RawInjection;

import java.time.Instant;

/**
 * 厂商适配器：把一家仪器的原始导出物翻译成统一的 {@link RawInjection}。
 *
 * <p>面对"厂商软件五花八门"，本系统的策略是<b>只承认一层薄适配</b>：
 * <ul>
 *   <li>适配器唯一职责是"字节 → 规范对象"，不做任何业务判断；</li>
 *   <li>适配器<b>不能</b>过滤、挑选或省略进样。它读到几条就产出几条，
 *       序号原样传递；跳号由上层计划对账发现。</li>
 *   <li>适配器<b>不能</b>写结果或积分。积分只在核心层对封存的信号进行。</li>
 * </ul>
 * 因此换一家厂商只需新增一个适配器，防篡改与复核逻辑完全复用。
 */
public interface InstrumentAdapter {

    /** 适配器标识，与 {@code Instrument.adapterId} 对应。 */
    String adapterId();

    /** 该适配器能识别的原始文件格式名，用于入库前核对。 */
    String vendorFormatName();

    /**
     * 解析原始导出物。
     *
     * @param rawBytes 厂商原始文件字节，调用方负责计算其哈希
     * @param context  采集时的上下文（仪器、计划、谁触发采集）
     */
    IngestedRun parse(byte[] rawBytes, IngestContext context);

    /** 一次解析的产物：可能包含多针（例如整段序列导出）。 */
    record IngestedRun(java.util.List<ParsedInjection> injections, String vendorFormat, String rawPayloadHash) {
        public IngestedRun {
            injections = java.util.List.copyOf(injections);
        }
    }

    record ParsedInjection(
            long sequenceNo,
            String plannedKey,
            String sampleId,
            int runNumber,
            PlannedInjection.Purpose purpose,
            Instant acquiredAt,
            Chromatogram trace,
            String note
    ) {
    }

    record IngestContext(String instrumentId, String acquiredBy, String plannedKeyHint) {
    }
}
