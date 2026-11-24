package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

import java.time.Instant;

/**
 * 一次进样的原始记录——一经采集即封存，永不修改、永不删除。
 *
 * <p>这是整个系统对"删掉不合格那次重测"的第一道防线：进样不是"一个可增删的结果"，
 * 而是一条只可追加的事实。分析员能做的只有两种：
 * <ol>
 *   <li>声明一次新的、带原因的 {@link PlannedInjection.Purpose#REPEAT} 进样（留下记录）</li>
 *   <li>把某次进样标记为 {@link RunStatus#EXCLUDED_INVALID}（必须带原因，且仍留在记录集中）</li>
 * </ol>
 * 两者都<b>不删除</b>任何东西，只会让复核人看到更多而不是更少。
 *
 * @param sequenceNo 由适配器给出的仪器内单调序号。序号跳号即意味着仪器侧发生了本系统未见的动作。
 * @param traceHash  与 {@code trace.contentHash()} 必须一致；采集时计算并写入审计链。
 * @param rawPayloadHash 厂商原始导出文件（AIA/ANDI/CDF 等）的哈希，保留可回溯到源文件的能力。
 */
public record RawInjection(
        String injectionId,
        String instrumentId,
        long sequenceNo,
        String plannedKey,
        String sampleId,
        int runNumber,
        PlannedInjection.Purpose purpose,
        Instant acquiredAt,
        String acquiredBy,
        RunStatus status,
        Chromatogram trace,
        String traceHash,
        String rawPayloadHash,
        String vendorFileFormat,
        String note
) implements Canonical {

    public enum RunStatus {
        /** 正常执行完毕，可用于结果计算。 */
        COMPLETED,
        /** 仪器异常中断，样本不完整。 */
        ABORTED,
        /** 分析员声明该次进样无效（例如系统适用性失败、气泡、进样针堵塞）。必须带原因。 */
        EXCLUDED_INVALID
    }

    public RawInjection {
        if (injectionId == null || injectionId.isBlank()) {
            throw new IllegalArgumentException("injectionId 不能为空");
        }
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("序号从 1 开始");
        }
        if (trace == null) {
            throw new IllegalArgumentException("缺少原始信号");
        }
        String computed = trace.contentHash();
        if (traceHash == null || !traceHash.equals(computed)) {
            throw new IllegalArgumentException("traceHash 与信号内容不符，拒绝入库");
        }
        if (status == RunStatus.EXCLUDED_INVALID && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("排除一次进样必须写明原因");
        }
    }

    /** 可用于结果计算的进样。 */
    public boolean isUsable() {
        return status == RunStatus.COMPLETED;
    }

    public RawInjection withStatus(RunStatus newStatus, String reason) {
        return new RawInjection(injectionId, instrumentId, sequenceNo, plannedKey, sampleId, runNumber,
                purpose, acquiredAt, acquiredBy, newStatus, trace, traceHash, rawPayloadHash,
                vendorFileFormat, reason);
    }
}
