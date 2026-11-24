package org.qclab.cds.review;

import org.qclab.cds.audit.AuditLedger;
import org.qclab.cds.audit.ChainVerification;
import org.qclab.cds.domain.Batch;
import org.qclab.cds.domain.InjectionDisposition;
import org.qclab.cds.domain.IntegrationResult;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.domain.Peak;
import org.qclab.cds.domain.PlannedInjection;
import org.qclab.cds.domain.RawInjection;
import org.qclab.cds.domain.ReviewSignature;
import org.qclab.cds.domain.Specification;
import org.qclab.cds.service.IngestService;
import org.qclab.cds.service.PeakDelta;
import org.qclab.cds.service.ReviewService;
import org.qclab.cds.store.LabRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 批次异常筛查引擎。
 *
 * <p>复核人时间有限，因此这里的目标不是"把所有信息列出来"，而是<b>排序</b>：
 * <ol>
 *   <li><b>CRITICAL</b>：数据完整性破坏或典型的"让不合格变合格"模式，不解释不能放行；</li>
 *   <li><b>HIGH</b>：高风险操作，必须给出说明；</li>
 *   <li><b>MEDIUM / INFO</b>：正常但需要知情的合法操作，可批量确认。</li>
 * </ol>
 *
 * <p>设计原则：<b>每条发现都必须可被独立核验</b>。证据字段里放的是实体 ID、哈希、
 * 数值对比，复核人点开任何一个都能自己复核一遍，而不是相信引擎的打分。
 *
 * <p>同时，引擎<b>不判断"人是否有错"</b>——它只把不寻常的事排到前面。
 * 提示性规则（如 RAPID_REINTEGRATION）可能完全合法；是否接受由复核人决定，
 * 决定本身进入审计。
 */
public final class AnomalyDetector {

    /** 同一进样超过这个版本数视为异常频繁。 */
    private static final int REINTEGRATION_THRESHOLD = 3;
    /** 连续两次重新积分间隔短于此值时提示。 */
    private static final Duration RAPID_REINTEGRATION_WINDOW = Duration.ofMinutes(10);
    /** 结果距限度不足此比例时提示"贴线"。 */
    private static final double NEAR_LIMIT_MARGIN = 0.05;

    private final LabRepository repo;
    private final AuditLedger audit;
    private final ReviewService reviews;
    private final IngestService ingest;
    /** 必须与审计账本共用同一个时钟，否则处置时间会与链上时间线不一致。 */
    private final AuditLedger.Clock clock;

    public AnomalyDetector(LabRepository repo, AuditLedger audit, ReviewService reviews,
                           IngestService ingest, AuditLedger.Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.reviews = reviews;
        this.ingest = ingest;
        this.clock = clock;
    }

    /** 对整批做筛查。调用方通常是复核界面打开时。 */
    public ReviewScreening screenBatch(String batchId, String actor) {
        Batch batch = repo.batch(batchId)
                .orElseThrow(() -> new IllegalArgumentException("不存在的批次: " + batchId));
        List<Finding> findings = new ArrayList<>();

        // 先做一次全局失效扫描：捕获"复核后记录被改动"这类只能靠重算载荷发现的问题。
        // 放在筛查之前，使随后的 checkReviews() 看到的是已收敛的状态。
        reviews.sweepInvalidations(actor);

        checkAuditChain(findings);
        checkPlanAndSequence(batch, findings);
        checkExclusions(batch, findings);
        checkRepeatReasons(batch, findings);
        checkIntegrations(batch, findings);
        checkReviews(batch, findings);

        ReviewScreening screening = ReviewScreening.of(batchId, findings);
        auditScreening(actor, batchId, screening);
        return screening;
    }

    // ---- 审计链 ----

    private void checkAuditChain(List<Finding> findings) {
        ChainVerification cv = audit.verify();
        if (!cv.intact()) {
            TreeMap<String, String> ev = new TreeMap<>();
            ev.put("firstBrokenSequence", String.valueOf(cv.firstBrokenSequence()));
            ev.put("checkedRecords", Long.toString(cv.checkedRecords()));
            findings.add(new Finding(RuleCodes.AUDIT_CHAIN_BROKEN, Severity.CRITICAL, "audit", "ledger", null,
                    "审计链断裂",
                    "审计记录在序号 " + cv.firstBrokenSequence() + " 处无法通过校验，"
                            + "所有以此为基础的合规结论都不可靠。",
                    ev,
                    List.of("立即冻结放行", "从外部锚定/WORM 备份恢复链并调查断裂原因")));
        }
    }

    // ---- 计划/序号 ----

    private void checkPlanAndSequence(Batch batch, List<Finding> findings) {
        IngestService.Reconciliation rec = ingest.reconcile(batch.batchId());
        for (String missing : rec.missingPlannedKeys()) {
            TreeMap<String, String> ev = new TreeMap<>();
            ev.put("plannedKey", missing);
            ev.put("plannedCount", Integer.toString(rec.plannedCount()));
            ev.put("sealedCount", Integer.toString(rec.sealedCount()));
            findings.add(new Finding(RuleCodes.PLAN_GAP, Severity.CRITICAL, "batch", batch.batchId(),
                    "(批次 " + batch.batchId() + ")",
                    "计划内进样缺失",
                    "计划登记了 " + missing + "，但账本中没有对应的原始进样，也没有作废说明。",
                    ev,
                    List.of("确认该进样是否被删除或未采集", "要求提供仪器原始文件核对")));
        }
        for (Long gap : rec.sequenceGaps()) {
            TreeMap<String, String> ev = new TreeMap<>();
            ev.put("missingSequenceNo", Long.toString(gap));
            findings.add(new Finding(RuleCodes.SEQUENCE_GAP, Severity.CRITICAL, "batch", batch.batchId(),
                    "(批次 " + batch.batchId() + ")",
                    "仪器序号跳号",
                    "仪器内序号 " + gap + " 在账本中不存在，说明仪器侧发生了本系统未记录的动作。",
                    ev,
                    List.of("调取仪器本地日志核对这一针", "确认是否存在绕过本系统的采集")));
        }
    }

    // ---- 作废 ----

    private void checkExclusions(Batch batch, List<Finding> findings) {
        for (RawInjection raw : repo.rawsForBatch(batch.batchId())) {
            List<InjectionDisposition> disps = repo.dispositionsFor(raw.injectionId());
            for (InjectionDisposition d : disps) {
                if (d.kind() != InjectionDisposition.Kind.EXCLUDE) {
                    continue;
                }
                TreeMap<String, String> ev = new TreeMap<>();
                ev.put("injectionId", raw.injectionId());
                ev.put("sampleId", String.valueOf(raw.sampleId()));
                ev.put("runNumber", Integer.toString(raw.runNumber()));
                ev.put("reason", d.reason());
                ev.put("excludedBy", d.actor());
                ev.put("excludedAt", d.at().toString());
                ev.put("traceHash", raw.traceHash());
                // 是否"先出了结果再作废"：说明分析员看到了结果才决定弃用
                Optional<IntegrationVersion> v1 = repo.versionsForInjection(raw.injectionId()).stream().findFirst();
                if (v1.isPresent()) {
                    ev.put("firstIntegratedAt", v1.get().createdAt().toString());
                    ev.put("hadResultBeforeExclusion", "true");
                    findings.add(new Finding(RuleCodes.EXCLUSION_AFTER_RESULT, Severity.HIGH,
                            "rawInjection", raw.injectionId(), raw.sampleId(),
                            "进样已有结果后被作废",
                            "该进样先被积分出结果，随后被声明无效。这是常见的'看到不合格再弃用'路径，需要理由充分。",
                            ev,
                            List.of("核对作废理由是否在结果产生之前就成立",
                                    "对照仪器原始文件确认进样确有异常")));
                    continue;
                }
                findings.add(new Finding(RuleCodes.INJECTION_EXCLUDED, Severity.MEDIUM,
                        "rawInjection", raw.injectionId(), raw.sampleId(),
                        "进样被作废",
                        "分析员声明该次进样无效：" + d.reason(),
                        ev,
                        List.of("确认理由合理", "如属误判可恢复并说明")));
            }
        }
    }

    // ---- 积分 ----

    private void checkIntegrations(Batch batch, List<Finding> findings) {
        Specification spec = batch.specification();
        for (RawInjection raw : repo.rawsForBatch(batch.batchId())) {
            List<IntegrationVersion> versions = repo.versionsForInjection(raw.injectionId());
            if (versions.isEmpty()) {
                continue;
            }
            IntegrationVersion latest = versions.get(versions.size() - 1);

            // 注意：这里**不**因进样被作废而跳过整个循环。
            // 版本链反映的是"分析员做过什么"——反复重积分、让峰消失、把结果调到合格，
            // 这些行为即使该进样随后被作废也依然发生过了，且作废本身可能就是掩盖的一环
            // （先改参数让峰消失，再声明作废，两步都会留痕）。
            // 只有"当前结果"类判定（超限/贴线/当前版本手工编辑）才应排除作废进样，
            // 见下方 currentUsable 的判断。
            boolean currentUsable =
                    ingest.effectiveStatus(raw.injectionId()) == RawInjection.RunStatus.COMPLETED;

            // 版本过多
            if (versions.size() >= REINTEGRATION_THRESHOLD) {
                TreeMap<String, String> ev = new TreeMap<>();
                ev.put("injectionId", raw.injectionId());
                ev.put("versionCount", Integer.toString(versions.size()));
                for (int i = 0; i < versions.size(); i++) {
                    IntegrationVersion v = versions.get(i);
                    ev.put("v" + v.versionNo(),
                            "result=" + org.qclab.cds.crypto.Canon.num(v.result().impurityPercent())
                                    + " reason=" + String.valueOf(v.reason())
                                    + " at=" + v.createdAt());
                }
                findings.add(new Finding(RuleCodes.REPEATED_REINTEGRATION, Severity.HIGH,
                        "rawInjection", raw.injectionId(), raw.sampleId(),
                        "同一进样被反复重新积分（" + versions.size() + " 版）",
                        "版本数达到 " + versions.size() + "，是'反复调参直到结果满意'的典型痕迹。",
                        ev,
                        List.of("逐版核对重新积分原因", "确认最终采用的版本在科学上站得住脚")));
            }

            // 短时间连续重积分
            for (int i = 1; i < versions.size(); i++) {
                IntegrationVersion prev = versions.get(i - 1);
                IntegrationVersion cur = versions.get(i);
                Duration gap = Duration.between(prev.createdAt(), cur.createdAt());
                if (!gap.isNegative() && gap.compareTo(RAPID_REINTEGRATION_WINDOW) < 0) {
                    TreeMap<String, String> ev = new TreeMap<>();
                    ev.put("fromVersion", prev.versionId());
                    ev.put("toVersion", cur.versionId());
                    ev.put("gapSeconds", Long.toString(gap.getSeconds()));
                    ev.put("reason", String.valueOf(cur.reason()));
                    findings.add(new Finding(RuleCodes.RAPID_REINTEGRATION, Severity.MEDIUM,
                            "integrationVersion", cur.versionId(), raw.sampleId(),
                            "两次重新积分间隔很短",
                            "距上一次重新积分仅 " + gap.toMinutes() + " 分钟，需确认不是顺手调参。",
                            ev, List.of("核对时间线与操作意图")));
                }
            }

            // 逐版对比：杂质峰消失 / 结果穿越限度
            for (int i = 1; i < versions.size(); i++) {
                IntegrationVersion prev = versions.get(i - 1);
                IntegrationVersion cur = versions.get(i);
                PeakDelta delta = PeakDelta.compute(prev, cur);
                if (delta.impurityDisappeared()) {
                    findings.add(new Finding(RuleCodes.IMPURITY_PEAK_DISAPPEARED, Severity.CRITICAL,
                            "integrationVersion", cur.versionId(), raw.sampleId(),
                            "重新积分使杂质峰消失",
                            "从 v" + prev.versionNo() + " 到 v" + cur.versionNo()
                                    + "，有 " + delta.disappeared().size() + " 个峰消失，其中含杂质峰。"
                                    + "这正是'改积分参数让杂质峰消失'的直接证据。",
                            delta.evidence(),
                            List.of("要求分析员解释参数改动的科学依据",
                                    "以不受该参数影响的独立方法复测",
                                    "考虑采用更保守的版本作为报告值")));
                }
                if (crossesSpec(prev.result(), cur.result(), spec)) {
                    TreeMap<String, String> ev = delta.evidence();
                    ev.put("limit", org.qclab.cds.crypto.Canon.num(spec.limit()));
                    ev.put("limitType", spec.limitType().name());
                    ev.put("prevPassed", Boolean.toString(!spec.isOutOfSpec(prev.result().impurityPercent())));
                    ev.put("curPassed", Boolean.toString(!spec.isOutOfSpec(cur.result().impurityPercent())));

                    // 关键区分：结果由不合格变合格，但"峰还在、只是归属变了"与
                    // "峰整个没了"是性质完全不同的两件事。
                    //   - 峰仍在（仅类型/归属变化）→ 通常是方法修正，MEDIUM，需说明但不判造假；
                    //   - 峰消失 → 参数把物质"积没了"，CRITICAL。
                    // 不区分这一点，会把正常的积分方法修正当成造假，导致复核人对告警麻木。
                    boolean peakStillPresentButReclassified = delta.disappeared().isEmpty()
                            && !delta.changed().isEmpty();
                    if (peakStillPresentButReclassified) {
                        ev.put("interpretation", "峰仍存在，仅归属/类型发生变化");
                        findings.add(new Finding(RuleCodes.SPEC_CROSSING_BY_REINTEGRATION, Severity.MEDIUM,
                                "integrationVersion", cur.versionId(), raw.sampleId(),
                                "重新积分使结果由不合格变为合格（峰仍存在）",
                                "杂质百分比从 " + fmt(prev.result().impurityPercent()) + "% 变为 "
                                        + fmt(cur.result().impurityPercent()) + "%，跨过限度 "
                                        + fmt(spec.limit()) + "%；但被检出的峰均未消失，"
                                        + "变化来自峰归属而非峰被滤除。需确认归属规则改动有理有据。",
                                ev,
                                List.of("核对方法版本变更的批准记录",
                                        "确认主峰归属窗口的设定有依据")));
                    } else {
                        findings.add(new Finding(RuleCodes.SPEC_CROSSING_BY_REINTEGRATION, Severity.CRITICAL,
                                "integrationVersion", cur.versionId(), raw.sampleId(),
                                "重新积分使结果由不合格变为合格",
                                "杂质百分比从 " + fmt(prev.result().impurityPercent()) + "% 变为 "
                                        + fmt(cur.result().impurityPercent()) + "%，跨过限度 "
                                        + fmt(spec.limit()) + "%，且有峰在过程中消失。",
                                ev,
                                List.of("复核参数改动是否事先有验证",
                                        "独立复测确认",
                                        "若无法解释则按不合格处理")));
                    }
                }
            }

            // 以下三项判定的是"当前结果"，仅对仍在用的进样有意义。
            // 作废进样的数值不再代表任何将放行的结果，拿它比限度只会制造误导性噪声。
            if (!currentUsable) {
                continue;
            }

            // 当前结果超限 / 贴线
            double measured = latest.result().impurityPercent();
            if (spec.isOutOfSpec(measured)) {
                TreeMap<String, String> ev = new TreeMap<>();
                ev.put("versionId", latest.versionId());
                ev.put("measured", fmt(measured));
                ev.put("limit", fmt(spec.limit()));
                findings.add(new Finding(RuleCodes.OUT_OF_SPEC, Severity.MEDIUM,
                        "integrationVersion", latest.versionId(), raw.sampleId(),
                        "当前结果超出限度",
                        "杂质 " + fmt(measured) + "% 超过限度 " + fmt(spec.limit()) + "%，本应判为不合格。",
                        ev, List.of("确认该批次是否应拒绝放行")));
            } else if (spec.marginRatio(measured) < NEAR_LIMIT_MARGIN) {
                TreeMap<String, String> ev = new TreeMap<>();
                ev.put("versionId", latest.versionId());
                ev.put("measured", fmt(measured));
                ev.put("limit", fmt(spec.limit()));
                findings.add(new Finding(RuleCodes.NEAR_LIMIT, Severity.INFO,
                        "integrationVersion", latest.versionId(), raw.sampleId(),
                        "结果贴近限度",
                        "杂质 " + fmt(measured) + "% 距限度 " + fmt(spec.limit()) + "% 很近，建议确认积分稳健性。",
                        ev, List.of("核对积分参数是否对结果敏感")));
            }

            // 手工编辑
            List<Peak> edited = latest.result().manuallyEditedPeaks();
            if (!edited.isEmpty()) {
                TreeMap<String, String> ev = new TreeMap<>();
                ev.put("versionId", latest.versionId());
                ev.put("editedPeakCount", Integer.toString(edited.size()));
                for (int i = 0; i < edited.size(); i++) {
                    Peak p = edited.get(i);
                    ev.put("edited." + i, "rt=" + p.retentionTimeMin() + " area=" + p.area()
                            + " type=" + p.peakType());
                }
                findings.add(new Finding(RuleCodes.MANUAL_PEAK_EDIT, Severity.MEDIUM,
                        "integrationVersion", latest.versionId(), raw.sampleId(),
                        "结果中含手工编辑的峰",
                        latest.versionId() + " 有 " + edited.size() + " 个峰被手工增删/调整。",
                        ev, List.of("核对每个手工编辑的合理依据")));
            }
        }
    }

    // ---- 重进样 ----

    private void checkRepeatReasons(Batch batch, List<Finding> findings) {
        for (RawInjection raw : repo.rawsForBatch(batch.batchId())) {
            if (raw.purpose() == PlannedInjection.Purpose.REPEAT
                    && (raw.note() == null || raw.note().isBlank())) {
                TreeMap<String, String> ev = new TreeMap<>();
                ev.put("injectionId", raw.injectionId());
                ev.put("runNumber", Integer.toString(raw.runNumber()));
                findings.add(new Finding(RuleCodes.REPEAT_WITHOUT_REASON, Severity.HIGH,
                        "rawInjection", raw.injectionId(), raw.sampleId(),
                        "重进样未写明原因",
                        "分析员采集了一次重进样但没有记录原因，无法判断它是正常的复测还是掩盖前一次不合格。",
                        ev, List.of("要求补充重进样原因")));
            }
        }
    }

    // ---- 复核状态 ----
    //
    // 这里只报告"复核之后记录被改动"这类<b>可疑</b>情况。
    // "某条最新结果还没复核"不在此列——那是放行门槛（ReleaseGate）的职责：
    // 复核队列里的每一条本来就都还没复核，把它当成告警会让 blocking 恒为真，
    // 告警从此失去区分度。

    private void checkReviews(Batch batch, List<Finding> findings) {
        for (RawInjection raw : repo.rawsForBatch(batch.batchId())) {
            for (IntegrationVersion version : repo.versionsForInjection(raw.injectionId())) {
                for (ReviewSignature s : repo.signaturesForVersion(version.versionId())) {
                    // 状态以审计链重放为准，不信任签名表里的 status 列
                    ReviewSignature.Status effective = reviews.effectiveStatus(s);
                    if (effective == ReviewSignature.Status.VALID) {
                        ReviewService.VerificationResult vr = reviews.verify(s);
                        if (!vr.valid()) {
                            TreeMap<String, String> ev = new TreeMap<>();
                            ev.put("signatureId", s.signatureId());
                            ev.put("versionId", s.versionId());
                            ev.put("reviewer", s.reviewerId());
                            ev.put("boundPayloadHash", s.boundPayloadHash());
                            ev.put("reason", vr.reason());
                            findings.add(new Finding(RuleCodes.REVIEW_INVALIDATED, Severity.CRITICAL,
                                    "reviewSignature", s.signatureId(), raw.sampleId(),
                                    "复核签名失效",
                                    "签名在复核之后不再成立：" + vr.reason()
                                            + "。记录可能在复核后被改动。",
                                    ev,
                                    List.of("立即冻结该结果", "查审计链定位改动者与改动内容", "重新复核当前版本")));
                        }
                    } else if (effective == ReviewSignature.Status.INVALIDATED) {
                        // 扫描（sweepInvalidations）已判定该签名不再可验证。这是最高级告警：
                        // 复核之后记录被改动，无论改动者是谁，此结果都不能再被信任。
                        TreeMap<String, String> ev = new TreeMap<>();
                        ev.put("signatureId", s.signatureId());
                        ev.put("versionId", s.versionId());
                        ev.put("reviewer", s.reviewerId());
                        ev.put("boundPayloadHash", s.boundPayloadHash());
                        ev.put("invalidationReason", String.valueOf(s.invalidationReason()));
                        findings.add(new Finding(RuleCodes.REVIEW_INVALIDATED, Severity.CRITICAL,
                                "reviewSignature", s.signatureId(), raw.sampleId(),
                                "复核签名已失效",
                                "签名在复核之后不再成立：" + s.invalidationReason()
                                        + "。结果或其来源数据在复核后被改动。",
                                ev,
                                List.of("立即冻结该结果", "查审计链定位改动者与改动内容", "重新复核当前版本")));
                    } else if (effective == ReviewSignature.Status.SUPERSEDED) {
                        TreeMap<String, String> ev = new TreeMap<>();
                        ev.put("signatureId", s.signatureId());
                        ev.put("versionId", s.versionId());
                        ev.put("reviewer", s.reviewerId());
                        ev.put("note", "该版本已被更晚的积分版本取代");
                        findings.add(new Finding(RuleCodes.REVIEW_SUPERSEDED, Severity.INFO,
                                "reviewSignature", s.signatureId(), raw.sampleId(),
                                "历史版本的复核签名已被取代",
                                "复核人当初签的是 v" + version.versionNo() + "，此后该进样产生了新版本。",
                                ev, List.of("确认新版本的复核已完成")));
                    }
                }
            }
        }
    }

    // ---- 辅助 ----

    /**
     * 记录复核人对某条发现的处置。<b>不删除发现</b>，只是追加一条可审计的意见。
     *
     * @throws IllegalStateException 若复核人无复核权限
     */
    public FindingDisposition recordDisposition(Finding finding, FindingDisposition.Decision decision,
                                                org.qclab.cds.domain.Operator reviewer, String rationale) {
        if (!reviewer.role().canReview()) {
            throw new IllegalStateException("角色 " + reviewer.role() + " 无复核权限");
        }
        FindingDisposition d = new FindingDisposition(
                "EXPL-" + java.util.UUID.randomUUID(),
                FindingDisposition.keyOf(finding),
                finding.code(),
                finding.subjectId(),
                decision,
                reviewer.operatorId(),
                rationale,
                clock.now());
        repo.addExplanation(d);

        TreeMap<String, String> details = new TreeMap<>();
        details.put("findingKey", d.findingKey());
        details.put("ruleCode", finding.code());
        details.put("severity", finding.severity().name());
        details.put("decision", decision.name());
        details.put("rationale", rationale);
        details.put("subjectId", finding.subjectId());
        audit.record(reviewer.operatorId(), "FINDING_DISPOSITION", "finding", d.findingKey(), details);
        return d;
    }

    private static boolean crossesSpec(IntegrationResult before, IntegrationResult after, Specification spec) {
        boolean beforeOos = spec.isOutOfSpec(before.impurityPercent());
        boolean afterOos = spec.isOutOfSpec(after.impurityPercent());
        return beforeOos && !afterOos;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private void auditScreening(String actor, String batchId, ReviewScreening screening) {
        TreeMap<String, String> details = new TreeMap<>();
        details.put("batchId", batchId);
        details.put("findingCount", Integer.toString(screening.findings().size()));
        details.put("blocking", Boolean.toString(screening.blocking()));
        screening.countsBySeverity().forEach((k, v) -> details.put("severity." + k, Long.toString(v)));
        screening.countsByCode().forEach((k, v) -> details.put("code." + k, Long.toString(v)));
        audit.record(actor, "REVIEW_SCREENING_RUN", "batch", batchId, details);
    }
}
