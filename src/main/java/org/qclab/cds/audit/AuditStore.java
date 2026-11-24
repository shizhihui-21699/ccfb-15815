package org.qclab.cds.audit;

import java.util.List;
import java.util.Optional;

/**
 * 审计记录的存储。刻意只暴露"追加"与"按序读取"，
 * <b>没有 update / delete / truncate</b>——不是靠纪律，是靠接口不存在这些方法。
 *
 * <p>生产实现应落在 append-only 介质上（WORM 存储、只追加的表 + 数据库触发器
 * 拒绝 UPDATE/DELETE、或外部不可变日志服务），并且应有外部锚定：
 * 定期把当前链头哈希写入法律时间戳/对象锁/WORM 桶，使"重写整条链"也不可行。
 */
public interface AuditStore {

    void append(AuditRecord record);

    Optional<AuditRecord> last();

    List<AuditRecord> all();

    List<AuditRecord> since(long sequenceExclusive);

    long size();
}
