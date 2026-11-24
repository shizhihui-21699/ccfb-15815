package org.qclab.cds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qclab.cds.audit.AuditLedger;
import org.qclab.cds.audit.AuditRecord;
import org.qclab.cds.audit.ChainVerification;
import org.qclab.cds.audit.InMemoryAuditStore;
import org.qclab.cds.demo.Lab;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计链测试。
 *
 * <p>这里用反射改写存储内部的记录来模拟"数据库管理员绕过应用层直改数据"。
 * 生产实现应把存储放在 WORM 介质上，使这种改写连物理上都做不到；
 * 但即使做到，链校验也必须能发现——这就是纵深防御。
 */
class AuditChainTest {

    private static AuditLedger ledger() {
        return new AuditLedger(new InMemoryAuditStore(),
                AuditLedger.Clock.ticking(Instant.parse("2026-01-15T07:00:00Z")));
    }

    @Test
    @DisplayName("正常写入的链校验通过，且哈希逐条衔接")
    void healthyChainVerifies() {
        AuditLedger l = ledger();
        l.record("u1", "A", "e", "1");
        l.record("u1", "B", "e", "2");
        l.record("u2", "C", "e", "3");

        ChainVerification cv = l.verify();
        assertTrue(cv.intact(), cv.summary());
        assertEquals(3, cv.checkedRecords());

        List<AuditRecord> all = l.all();
        for (int i = 1; i < all.size(); i++) {
            assertEquals(all.get(i - 1).recordHash(), all.get(i).previousHash(),
                    "第 " + i + " 条的 previousHash 应等于前一条的 recordHash");
        }
        assertEquals(AuditRecord.GENESIS_PREV, all.get(0).previousHash());
    }

    @Test
    @DisplayName("篡改任一条记录的内容会被链校验定位到具体序号")
    void tamperingContentIsDetectedWithSequence() throws Exception {
        AuditLedger l = ledger();
        l.record("alice", "INTEGRATION_V1", "version", "V-1");
        l.record("alice", "REINTEGRATION", "version", "V-2");
        l.record("alice", "EXPORT", "version", "V-2");

        // 直接把第 2 条的操作员改成别人——模拟 DBA 改库
        tamperActor(l, 2, "mallory");

        ChainVerification cv = l.verify();
        assertFalse(cv.intact(), "篡改必须被发现");
        assertEquals(2L, cv.firstBrokenSequence(), "应定位到第 2 条");
        assertTrue(cv.summary().contains("改写"), cv.summary());
    }

    @Test
    @DisplayName("删除中间一条记录会让后续衔接全部失败")
    void deletingMiddleRecordBreaksChain() throws Exception {
        AuditLedger l = ledger();
        l.record("alice", "A", "e", "1");
        l.record("alice", "B", "e", "2");
        l.record("alice", "C", "e", "3");

        removeRecord(l, 2);

        ChainVerification cv = l.verify();
        assertFalse(cv.intact());
        // 删掉后原第 3 条变成第 2 位，但它的 sequence 仍是 3 → 序号不连续
        assertEquals(3L, cv.firstBrokenSequence());
        assertTrue(cv.summary().contains("序号不连续"), cv.summary());
    }

    @Test
    @DisplayName("整体重算链也无法本地掩盖：头部哈希会变化，外部锚定可识别")
    void wholesaleRewriteChangesHeadHash() throws Exception {
        AuditLedger l = ledger();
        l.record("alice", "A", "e", "1");
        l.record("alice", "B", "e", "2");
        String originalHead = l.headHash();

        // 攻击者重写整条链，使内部完全自洽
        recomputeWholeChain(l, "mallory");
        ChainVerification cv = l.verify();
        assertTrue(cv.intact(), "重算后的链在内部是自洽的——这正是需要外部锚定的原因");
        assertNotEquals(originalHead, l.headHash(),
                "只要链内容变了，头部哈希必然变化；把它锚定到 WORM/时间戳即可识破重写");
    }

    @Test
    @DisplayName("时间戳倒流被识别")
    void nonMonotonicTimestampsDetected() throws Exception {
        InMemoryAuditStore store = new InMemoryAuditStore();
        AuditLedger l = new AuditLedger(store, AuditLedger.Clock.fixed(Instant.parse("2026-01-15T07:00:00Z")));
        l.record("u", "A", "e", "1");
        l.record("u", "B", "e", "2");   // 同一时刻

        Field f = InMemoryAuditStore.class.getDeclaredField("records");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<AuditRecord> records = (List<AuditRecord>) f.get(store);
        // 把第 2 条的时间改到第 1 条之前，并重算它的哈希，使其内部自洽
        AuditRecord r2 = records.get(1);
        AuditRecord early = new AuditRecord(r2.sequence(), Instant.parse("2026-01-14T00:00:00Z"),
                r2.actor(), r2.action(), r2.entityType(), r2.entityId(), r2.previousHash(), "",
                r2.details()).withHash();
        records.set(1, early);

        ChainVerification cv = l.verify();
        assertFalse(cv.intact());
        assertTrue(cv.summary().contains("倒流"), cv.summary());
    }

    @Test
    @DisplayName("每条业务动作都在链上，且 disclosure 字段可查")
    void businessActionsAreAllRecorded() {
        Lab lab = Lab.createDeterministic(Instant.parse("2026-01-15T07:00:00Z"));
        org.qclab.cds.demo.FraudScenario.build(lab);

        List<String> actions = lab.audit.all().stream().map(AuditRecord::action).toList();
        assertTrue(actions.contains("PLAN_REGISTERED"));
        assertTrue(actions.contains("INJECTION_SEALED"));
        assertTrue(actions.contains("INTEGRATION_V1"));
        assertTrue(actions.contains("REINTEGRATION"), "重新积分必须留痕");
        assertTrue(actions.contains("INJECTION_EXCLUDED"), "作废必须留痕");
        assertTrue(lab.audit.verify().intact());
    }

    @Test
    @DisplayName("存储拒绝写入哈希不符的记录")
    void storeRejectsInconsistentRecord() {
        InMemoryAuditStore store = new InMemoryAuditStore();
        AuditRecord forged = new AuditRecord(1, Instant.EPOCH, "u", "A", "e", "1",
                AuditRecord.GENESIS_PREV, "f".repeat(64), new TreeMap<>());
        assertThrows(IllegalStateException.class, () -> store.append(forged),
                "recordHash 与内容不符时必须拒绝");
    }

    // ---- 反射辅助：模拟绕过应用层的直改 ----

    @SuppressWarnings("unchecked")
    private static List<AuditRecord> recordsOf(AuditLedger l) throws Exception {
        Field storeField = AuditLedger.class.getDeclaredField("store");
        storeField.setAccessible(true);
        Object store = storeField.get(l);
        Field f = InMemoryAuditStore.class.getDeclaredField("records");
        f.setAccessible(true);
        return (List<AuditRecord>) f.get(store);
    }

    private static void tamperActor(AuditLedger l, int index, String newActor) throws Exception {
        List<AuditRecord> records = recordsOf(l);
        AuditRecord r = records.get(index - 1);
        records.set(index - 1, new AuditRecord(r.sequence(), r.timestamp(), newActor, r.action(),
                r.entityType(), r.entityId(), r.previousHash(), r.recordHash(), r.details()));
    }

    private static void removeRecord(AuditLedger l, int index) throws Exception {
        recordsOf(l).remove(index - 1);
    }

    /** 重算整条链：从第 1 条起把 actor 全换成 mallory，并重新计算所有哈希。 */
    private static void recomputeWholeChain(AuditLedger l, String actor) throws Exception {
        List<AuditRecord> records = recordsOf(l);
        List<AuditRecord> rebuilt = new ArrayList<>();
        String prev = AuditRecord.GENESIS_PREV;
        for (AuditRecord r : records) {
            AuditRecord next = new AuditRecord(r.sequence(), r.timestamp(), actor, r.action(),
                    r.entityType(), r.entityId(), prev, "", r.details()).withHash();
            rebuilt.add(next);
            prev = next.recordHash();
        }
        records.clear();
        records.addAll(rebuilt);
    }
}
