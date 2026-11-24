package org.qclab.cds.review;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 一次复核筛查的完整结果。
 *
 * <p>面向复核人的界面只需要三块：<b>必须先处理的</b>（CRITICAL/HIGH）、
 * <b>可以批量确认的</b>（MEDIUM/INFO）、<b>证据</b>。这里提供聚合视图。
 */
public record ReviewScreening(
        String batchId,
        List<Finding> findings,
        Map<String, Long> countsBySeverity,
        Map<String, Long> countsByCode,
        boolean blocking
) {

    public static ReviewScreening of(String batchId, List<Finding> findings) {
        List<Finding> sorted = findings.stream()
                .sorted(java.util.Comparator.comparingInt(Finding::sortKey)
                        .thenComparing(Finding::code)
                        .thenComparing(Finding::subjectId))
                .toList();
        Map<String, Long> bySeverity = sorted.stream()
                .collect(Collectors.groupingBy(f -> f.severity().name(), TreeMap::new, Collectors.counting()));
        Map<String, Long> byCode = sorted.stream()
                .collect(Collectors.groupingBy(Finding::code, TreeMap::new, Collectors.counting()));
        boolean blocking = sorted.stream()
                .anyMatch(f -> f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH);
        return new ReviewScreening(batchId, sorted, bySeverity, byCode, blocking);
    }

    public List<Finding> requiringAttention() {
        return findings.stream()
                .filter(f -> f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH)
                .toList();
    }

    public List<Finding> informational() {
        return findings.stream()
                .filter(f -> f.severity() != Severity.CRITICAL && f.severity() != Severity.HIGH)
                .toList();
    }
}
