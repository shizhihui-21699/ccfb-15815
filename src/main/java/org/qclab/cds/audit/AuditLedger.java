package org.qclab.cds.audit;

import org.qclab.cds.crypto.Hashing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 审计账本的唯一写入口。所有业务动作都必须经过 {@link #record}。
 *
 * <p>设计上有意把"时间戳"与"序号"都收在这里：调用方不能自带时间戳，
 * 否则可以伪造"我是 9 点整做的"来制造不在场证明。
 */
public final class AuditLedger {

    private final AuditStore store;
    private final Clock clock;

    public AuditLedger(AuditStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public static AuditLedger withSystemClock() {
        return new AuditLedger(new InMemoryAuditStore(), Clock.system());
    }

    public synchronized AuditRecord record(String actor, String action, String entityType, String entityId,
                                           TreeMap<String, String> details) {
        AuditRecord last = store.last().orElse(null);
        long seq = last == null ? 1 : last.sequence() + 1;
        String prev = last == null ? AuditRecord.GENESIS_PREV : last.recordHash();
        AuditRecord pending = new AuditRecord(seq, clock.now(), actor, action, entityType, entityId,
                prev, "", details == null ? new TreeMap<>() : details);
        AuditRecord sealed = pending.withHash();
        store.append(sealed);
        return sealed;
    }

    public AuditRecord record(String actor, String action, String entityType, String entityId) {
        return record(actor, action, entityType, entityId, null);
    }

    /**
     * 逐条校验：哈希内容、前后衔接、序号连续、时间不倒流。
     * 任何一处不符都定位到具体序号，供复核界面直接跳转。
     */
    public ChainVerification verify() {
        List<AuditRecord> records = store.all();
        String expectedPrev = AuditRecord.GENESIS_PREV;
        Instant lastTs = null;
        long expectedSeq = 1;
        for (AuditRecord r : records) {
            if (r.sequence() != expectedSeq) {
                return ChainVerification.broken(expectedSeq - 1, r.sequence(),
                        List.of("序号不连续：期望 " + expectedSeq + "，实际 " + r.sequence()));
            }
            if (!r.previousHash().equals(expectedPrev)) {
                return ChainVerification.broken(expectedSeq - 1, r.sequence(),
                        List.of("前一记录哈希不符：期望 " + expectedPrev + "，实际 " + r.previousHash()));
            }
            if (!r.recordHash().equals(r.computeHash())) {
                return ChainVerification.broken(expectedSeq - 1, r.sequence(),
                        List.of("记录内容与其哈希不符（记录被改写）"));
            }
            if (lastTs != null && r.timestamp().isBefore(lastTs)) {
                return ChainVerification.broken(expectedSeq - 1, r.sequence(),
                        List.of("时间戳倒流：" + r.timestamp() + " 早于 " + lastTs));
            }
            expectedPrev = r.recordHash();
            lastTs = r.timestamp();
            expectedSeq++;
        }
        return ChainVerification.ok(records.size());
    }

    /** 当前链头哈希。外部锚定（时间戳、WORM）应固定这个值。 */
    public String headHash() {
        return store.last().map(AuditRecord::recordHash).orElse(AuditRecord.GENESIS_PREV);
    }

    public Optional<AuditRecord> last() {
        return store.last();
    }

    public List<AuditRecord> all() {
        return store.all();
    }

    /** 供测试注入确定性时间，避免时间相关断言不稳定。 */
    public interface Clock {
        Instant now();

        static Clock system() {
            return Instant::now;
        }

        static Clock fixed(Instant t) {
            return () -> t;
        }

        static Clock ticking(Instant start) {
            return new FixedAdvancingClock(start);
        }
    }

    /** 每读一次前进 1 毫秒，保证时间严格递增且可预测。 */
    static final class FixedAdvancingClock implements Clock {
        private Instant current;

        FixedAdvancingClock(Instant start) {
            this.current = start;
        }

        @Override
        public Instant now() {
            Instant t = current;
            current = current.plusMillis(1);
            return t;
        }
    }

    public static String shortHash(String h) {
        return h == null ? "-" : h.substring(0, Math.min(12, h.length()));
    }

    public static String hashOf(String s) {
        return Hashing.sha256Hex(s);
    }
}
