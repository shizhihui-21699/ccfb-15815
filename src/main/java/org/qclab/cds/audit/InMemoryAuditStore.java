package org.qclab.cds.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 内存实现，仅用于开发与测试。生产请用 {@link AuditStore} 的持久实现。
 *
 * <p>即便如此，本实现也刻意不提供修改入口：一旦 append 出去，对象就不再被触碰。
 * 若测试或代码试图通过反射改写，链校验会失败——这正是我们要演示的行为。
 */
public final class InMemoryAuditStore implements AuditStore {

    private final List<AuditRecord> records = new ArrayList<>();

    @Override
    public synchronized void append(AuditRecord record) {
        if (!records.isEmpty()) {
            AuditRecord last = records.get(records.size() - 1);
            if (record.sequence() != last.sequence() + 1) {
                throw new IllegalStateException("审计序号必须连续：期望 " + (last.sequence() + 1)
                        + "，实际 " + record.sequence());
            }
            if (!record.previousHash().equals(last.recordHash())) {
                throw new IllegalStateException("审计链断开：previousHash 与上一条 recordHash 不符");
            }
        } else if (record.sequence() != 1) {
            throw new IllegalStateException("首条审计记录序号必须为 1");
        }
        if (!record.recordHash().equals(record.computeHash())) {
            throw new IllegalStateException("审计记录哈希与内容不符，拒绝写入");
        }
        records.add(record);
    }

    @Override
    public synchronized Optional<AuditRecord> last() {
        return records.isEmpty() ? Optional.empty() : Optional.of(records.get(records.size() - 1));
    }

    @Override
    public synchronized List<AuditRecord> all() {
        return List.copyOf(records);
    }

    @Override
    public synchronized List<AuditRecord> since(long sequenceExclusive) {
        List<AuditRecord> out = new ArrayList<>();
        for (AuditRecord r : records) {
            if (r.sequence() > sequenceExclusive) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public synchronized long size() {
        return records.size();
    }
}
