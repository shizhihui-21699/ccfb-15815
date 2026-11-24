package org.qclab.cds.audit;

import org.qclab.cds.crypto.Canon;
import org.qclab.cds.crypto.Canonical;

import java.time.Instant;
import java.util.TreeMap;

/**
 * 一条审计记录。
 *
 * <p>防篡改由三件事共同保证，缺一不可：
 * <ol>
 *   <li>{@code previousHash} 把记录串成链——删中间一条会让后续所有哈希对不上；</li>
 *   <li>{@code recordHash} 覆盖本条全部字段——改任一字段（包括时间、操作员、原因）都会让它失配；</li>
 *   <li>背书（{@link AuditLedger} 的签名/锚定）把最新哈希固定到外部——防止整批重算。</li>
 * </ol>
 *
 * <p>注意 {@code recordHash} <b>不</b>参与自身计算，因此它是"内容哈希"而非"签名哈希"。
 */
public record AuditRecord(
        long sequence,
        Instant timestamp,
        String actor,
        String action,
        String entityType,
        String entityId,
        String previousHash,
        String recordHash,
        TreeMap<String, String> details
) implements Canonical {

    public static final String GENESIS_PREV = "0000000000000000000000000000000000000000000000000000000000000000";

    public AuditRecord {
        if (sequence < 1) {
            throw new IllegalArgumentException("审计序号从 1 开始");
        }
        details = details == null ? new TreeMap<>() : new TreeMap<>(details);
    }

    /**
     * 计算本条记录的哈希。{@code recordHash} 字段本身不参与，
     * 因此本方法可在构造正式记录之前调用（构造 pending 记录时用）。
     */
    public String computeHash() {
        return org.qclab.cds.crypto.Hashing.sha256Hex(payload());
    }

    public String payload() {
        return Canon.kv(
                "seq", Canon.num(sequence),
                "ts", Canon.instant(timestamp),
                "actor", actor,
                "action", action,
                "entityType", entityType,
                "entityId", entityId,
                "prev", previousHash,
                "details", Canon.map(details)
        );
    }

    public AuditRecord withHash() {
        String h = computeHash();
        return new AuditRecord(sequence, timestamp, actor, action, entityType, entityId, previousHash, h, details);
    }
}
