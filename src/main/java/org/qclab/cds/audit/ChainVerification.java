package org.qclab.cds.audit;

import java.util.List;

/** 链校验结果。不通过时必须能指出"从第几条开始断"，否则等于没校验。 */
public record ChainVerification(boolean intact, long checkedRecords, Long firstBrokenSequence, List<String> problems) {

    public static ChainVerification ok(long n) {
        return new ChainVerification(true, n, null, List.of());
    }

    public static ChainVerification broken(long checked, long seq, List<String> problems) {
        return new ChainVerification(false, checked, seq, List.copyOf(problems));
    }

    /** 供复核界面使用的严重度：链断了是最高级告警。 */
    public String summary() {
        return intact
                ? "审计链完整，已校验 " + checkedRecords + " 条记录"
                : "审计链在序号 " + firstBrokenSequence + " 处断裂：" + String.join("；", problems);
    }
}
