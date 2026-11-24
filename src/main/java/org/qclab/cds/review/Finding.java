package org.qclab.cds.review;

import org.qclab.cds.crypto.Canonical;

import java.util.List;
import java.util.TreeMap;

/**
 * 一条异常发现。
 *
 * <p>关键设计：每条发现都必须携带<b>可核验的证据</b>（相关的进样、版本、哈希、数值对比），
 * 而不是一个"模型说可疑"的黑箱分数。复核人可以点开证据自己判断，
 * 也可以一键"接受并说明原因"从而把判断留给人的专业裁量——系统负责排序与呈现，
 * 不替人做法规判断。
 *
 * @param code       稳定的规则代码，供审计与统计（例如 REPEATED_REINTEGRATION）
 * @param subjectId  发现所指向的实体（进样 ID 或版本 ID）
 */
public record Finding(
        String code,
        Severity severity,
        String subjectType,
        String subjectId,
        String sampleId,
        String title,
        String detail,
        TreeMap<String, String> evidence,
        List<String> suggestedActions
) implements Canonical {

    public Finding {
        evidence = evidence == null ? new TreeMap<>() : new TreeMap<>(evidence);
        suggestedActions = suggestedActions == null ? List.of() : List.copyOf(suggestedActions);
    }

    /** 供排序：先按严重度，再按代码。 */
    public int sortKey() {
        return severity.ordinal();
    }
}
