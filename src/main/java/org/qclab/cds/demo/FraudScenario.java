package org.qclab.cds.demo;

import org.qclab.cds.domain.Batch;
import org.qclab.cds.domain.Instrument;
import org.qclab.cds.domain.IntegrationMethod;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.domain.PlannedInjection;
import org.qclab.cds.domain.RawInjection;
import org.qclab.cds.domain.Specification;
import org.qclab.cds.service.IngestService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个贴近真实的演示批次，同时包含<b>正常操作</b>与<b>三种典型造假手法</b>，
 * 用于展示异常筛查如何把复核人的注意力引到真正可疑的少数记录上。
 *
 * <p>场景：阿莫西林胶囊有关物质检测，规格为单个杂质 ≤ 0.50%。
 * <ul>
 *   <li><b>S-001（手法 A：删针）</b>——第一针杂质 0.57% 不合格。分析员把这一针从
 *       导出序列里删掉，直接以"重进样"名义交了第二针 0.31%。仪器序号因此跳号，
 *       计划里那一针也应到未到。两处都会报警。</li>
 *   <li><b>S-002（手法 B：改积分参数）</b>——首版积分得 0.57% 不合格。分析员抬高
 *       峰高阈值与方法参数重新积分，杂质峰"消失"，结果变成 0.00% 合格。
 *       版本链对比直接列出消失的峰。</li>
 *   <li><b>S-003（手法 C：出结果后作废）</b>——先正常积分得到 0.62%，看到不合格后
 *       声明该进样无效并重测。这本身合法，但"先有结果后有作废"必须要求说明。</li>
 *   <li><b>S-004（正常）</b>——一次通过，杂质 0.12%，重新积分也无异常。作为对照，
 *       证明筛查不会把正常数据淹没在告警里。</li>
 * </ul>
 */
public final class FraudScenario {

    public static final double SPEC_LIMIT = 0.50;

    public final Lab lab;
    public final Instrument instrument;
    public final Batch batch;
    public final List<RawInjection> sealed = new ArrayList<>();

    public RawInjection s001DeletedInjection;
    public RawInjection s001Repeat;
    public RawInjection s002Injection;
    public IntegrationVersion s002Strict;
    public IntegrationVersion s002Relaxed;
    public RawInjection s003Injection;
    public RawInjection s004Injection;

    private FraudScenario(Lab lab) {
        this.lab = lab;
        this.instrument = lab.instrument("HPLC-01", "Agilent", "1260 Infinity II", "DE12345678");
        Specification spec = new Specification("AMOX-500", "maxSingleImpurity", SPEC_LIMIT,
                Specification.LimitType.MAX, "ChP 2025");
        this.batch = lab.batch("BATCH-2026-001", "AMOX-500", spec, instrument);
    }

    public static FraudScenario build(Lab lab) {
        FraudScenario s = new FraudScenario(lab);
        s.registerPlans();
        s.runNormalSamplesFirst();
        s.fraudA_DroppedInjection();
        s.fraudB_Reintegration();
        s.fraudC_ExcludeAfterResult();
        // 序列结束，统一对账一次——缺口在这时才是有意义的结论，
        // 而不是采集中途每次导入都报一遍
        lab.audit.record(lab.analyst.operatorId(), "SEQUENCE_COMPLETED", "batch", s.batch.batchId());
        lab.ingest.reconcileAndReport(s.batch.batchId(), "system");
        return s;
    }

    /**
     * 计划 key 必须<b>唯一标识一次进样</b>，而不是"一个样品"。
     *
     * <p>这是个容易犯的设计错误：若重进样与原进样共用同一个 plannedKey，
     * 那么"原进样被删、只留下重进样"在按 key 对账时看起来完全正常，
     * PLAN_GAP 永远不会触发——造假者只需保留一条同名的重进样即可。
     * 因此这里按 {@code 批次#样品-R{run}} 编码。
     */
    private String key(String sampleId, int runNumber) {
        return batch.batchId() + "#" + sampleId + "-R" + runNumber;
    }

    private void registerPlans() {
        lab.ingest.registerPlan(batch, List.of(
                new PlannedInjection(key("SS-1", 1), "SS-1", 1, PlannedInjection.Purpose.SYSTEM_SUITABILITY),
                new PlannedInjection(key("S-001", 1), "S-001", 1, PlannedInjection.Purpose.SAMPLE),
                new PlannedInjection(key("S-002", 1), "S-002", 1, PlannedInjection.Purpose.SAMPLE),
                new PlannedInjection(key("S-003", 1), "S-003", 1, PlannedInjection.Purpose.SAMPLE),
                new PlannedInjection(key("S-004", 1), "S-004", 1, PlannedInjection.Purpose.SAMPLE)
        ));
        lab.audit.record(lab.analyst.operatorId(), "SEQUENCE_STARTED", "batch", batch.batchId(),
                new java.util.TreeMap<>(java.util.Map.of("instrument", instrument.instrumentId())));
    }

    /** 系统适用性 + 两个"干净"样品先跑，建立正常基线。 */
    private void runNormalSamplesFirst() {
        var ss = SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new SyntheticChromatogram.PeakSpec(2.0, 5, 0.05));
        var s004 = SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new SyntheticChromatogram.PeakSpec(4.2, 1.6, 0.05));

        var w = new VendorFileWriter()
                .injection(1, key("SS-1", 1), "SS-1", 1, PlannedInjection.Purpose.SYSTEM_SUITABILITY,
                        at("2026-01-15T08:00:00Z"), ss)
                .injection(2, key("S-004", 1), "S-004", 1, PlannedInjection.Purpose.SAMPLE,
                        at("2026-01-15T08:40:00Z"), s004);
        sealed.addAll(lab.ingest.ingest(w.bytes(), instrument.instrumentId(), batch.batchId(),
                lab.analyst.operatorId(), null));

        s004Injection = sealed.get(1);
        lab.integration.integrateFirstTime(s004Injection.injectionId(),
                lab.method(1, 6.0, 0.001), lab.analyst.operatorId());
    }

    /**
     * 手法 A：S-001 第一针不合格，被整针删除。
     * 真实的仪器侧序号是 3；分析员导出的文件里直接跳到了序号 4（重进样）。
     */
    private void fraudA_DroppedInjection() {
        // 这一针在仪器里确实采集过（序号 3），但从未进入本系统——
        // 它只存在于"计划"和"序号空洞"里。
        var waived = SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new SyntheticChromatogram.PeakSpec(4.2, 7.6, 0.06)); // 杂质约 0.57%

        // 分析员导出时从头文件里删掉了这一针，剩下的序号从 4 开始
        var repeat = SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new SyntheticChromatogram.PeakSpec(4.2, 1.9, 0.05)); // 约 0.14% 合格

        var w = new VendorFileWriter()
                .injection(4, key("S-001", 2), "S-001", 2, PlannedInjection.Purpose.REPEAT,
                        at("2026-01-15T09:10:00Z"), repeat);
        sealed.addAll(lab.ingest.ingest(w.bytes(), instrument.instrumentId(), batch.batchId(),
                lab.analyst.operatorId(), null));
        s001Repeat = sealed.get(sealed.size() - 1);

        // 分析员从未提交过 s-001 的第一针；这里保留一个引用，供演示与断言使用
        this.s001DeletedInjection = droppedInjectionStub(waived, 3);
        lab.integration.integrateFirstTime(s001Repeat.injectionId(),
                lab.method(1, 6.0, 0.001), lab.analyst.operatorId());
    }

    /** 手法 B：S-002 首版不合格，用放宽的方法重新积分让杂质峰"消失"。 */
    private void fraudB_Reintegration() {
        var trace = SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new SyntheticChromatogram.PeakSpec(4.2, 7.6, 0.06)); // 约 0.57%，不合格

        var w = new VendorFileWriter()
                .injection(5, key("S-002", 1), "S-002", 1, PlannedInjection.Purpose.SAMPLE,
                        at("2026-01-15T09:50:00Z"), trace);
        sealed.addAll(lab.ingest.ingest(w.bytes(), instrument.instrumentId(), batch.batchId(),
                lab.analyst.operatorId(), null));
        s002Injection = sealed.get(sealed.size() - 1);

        s002Strict = lab.integration.integrateFirstTime(s002Injection.injectionId(),
                lab.method(1, 6.0, 0.001), lab.analyst.operatorId());

        // 抬高峰高阈值到 20，杂质峰(高 12)不再被识别
        s002Relaxed = lab.integration.reintegrate(s002Injection.injectionId(),
                lab.relaxedMethod(2, 6.0), lab.analyst.operatorId(),
                "调整积分参数使基线更平滑");
    }

    /** 手法 C：S-003 先出结果，看到超限后作废并重测。 */
    private void fraudC_ExcludeAfterResult() {
        var trace = SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new SyntheticChromatogram.PeakSpec(4.2, 8.3, 0.06)); // 约 0.62%

        var w = new VendorFileWriter()
                .injection(6, key("S-003", 1), "S-003", 1, PlannedInjection.Purpose.SAMPLE,
                        at("2026-01-15T10:30:00Z"), trace);
        sealed.addAll(lab.ingest.ingest(w.bytes(), instrument.instrumentId(), batch.batchId(),
                lab.analyst.operatorId(), null));
        s003Injection = sealed.get(sealed.size() - 1);

        IntegrationVersion v1 = lab.integration.integrateFirstTime(s003Injection.injectionId(),
                lab.method(1, 6.0, 0.001), lab.analyst.operatorId());

        // 先有结果，再作废
        lab.ingest.exclude(s003Injection.injectionId(), "进样针有气泡，峰形异常", lab.analyst.operatorId());

        var retest = SyntheticChromatogram.create().duration(14).interval(0.005).build(
                new SyntheticChromatogram.PeakSpec(6.0, 1000, 0.08),
                new SyntheticChromatogram.PeakSpec(2.8, 3.0, 0.05));
        var w2 = new VendorFileWriter()
                .injection(7, key("S-003", 2), "S-003", 2, PlannedInjection.Purpose.REPEAT,
                        at("2026-01-15T11:05:00Z"), retest);
        lab.ingest.ingest(w2.bytes(), instrument.instrumentId(), batch.batchId(),
                lab.analyst.operatorId(), null);
    }

    /** 只用于呈现"被删掉的那一针"长什么样；它没有、也不应进入仓库。 */
    private RawInjection droppedInjectionStub(org.qclab.cds.domain.Chromatogram trace, long seq) {
        return new RawInjection("INJ-DELETED-S001-1", instrument.instrumentId(), seq,
                key("S-001", 1), "S-001", 1, PlannedInjection.Purpose.SAMPLE,
                at("2026-01-15T08:50:00Z"), lab.analyst.operatorId(),
                RawInjection.RunStatus.COMPLETED, trace, trace.contentHash(),
                "n/a", "vendor-csv-v1", "该针未进入本系统");
    }

    public IngestService.Reconciliation reconciliation() {
        return lab.ingest.reconcile(batch.batchId());
    }

    private String key(String sampleId) {
        return batch.batchId() + "#" + sampleId;
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    /** 断言用：严格方法下的杂质百分比。 */
    public double strictImpurityOf(RawInjection raw) {
        IntegrationMethod m = lab.method(1, 6.0, 0.001);
        return new org.qclab.cds.integration.PeakDetector()
                .integrate(raw.trace(), m).impurityPercent();
    }
}
