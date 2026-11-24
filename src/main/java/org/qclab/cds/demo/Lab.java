package org.qclab.cds.demo;

import org.qclab.cds.audit.AuditLedger;
import org.qclab.cds.crypto.Keyring;
import org.qclab.cds.domain.Batch;
import org.qclab.cds.domain.IntegrationMethod;
import org.qclab.cds.domain.Instrument;
import org.qclab.cds.domain.Operator;
import org.qclab.cds.domain.Role;
import org.qclab.cds.domain.Specification;
import org.qclab.cds.integration.PeakDetector;
import org.qclab.cds.ingest.CsvDemoAdapter;
import org.qclab.cds.review.AnomalyDetector;
import org.qclab.cds.service.Housekeeping;
import org.qclab.cds.service.IngestService;
import org.qclab.cds.service.IntegrationService;
import org.qclab.cds.service.ReviewService;
import org.qclab.cds.store.InMemoryLabRepository;
import org.qclab.cds.store.LabRepository;

import java.time.Instant;
import java.util.TreeMap;

/**
 * 演示/测试用的装配环境：把仓库、审计、密钥、各服务按生产依赖关系接好。
 *
 * <p>所有服务共享同一个 {@link AuditLedger} 与同一个时钟，
 * 这是"任何一个动作都进同一条链"的前提。
 */
public final class Lab {

    public final LabRepository repo;
    public final InMemoryLabRepository memory;
    public final AuditLedger audit;
    public final Keyring keyring;
    public final Housekeeping housekeeping;
    public final IngestService ingest;
    public final IntegrationService integration;
    public final ReviewService reviews;
    public final AnomalyDetector detector;
    public final org.qclab.cds.review.ReleaseGatekeeper gatekeeper;
    public final AuditLedger.Clock clock;

    public final Operator analyst;
    public final Operator reviewer;
    public final Operator qa;

    private Lab(InMemoryLabRepository repo, AuditLedger.Clock clock) {
        this.memory = repo;
        this.repo = repo;
        this.clock = clock;
        this.audit = new AuditLedger(new org.qclab.cds.audit.InMemoryAuditStore(), clock);
        this.keyring = new Keyring();
        this.housekeeping = new Housekeeping(repo, audit, clock);
        this.ingest = new IngestService(repo, audit, clock);
        this.ingest.registerAdapter(new CsvDemoAdapter());
        this.ingest.bindHousekeeping(housekeeping);
        this.integration = new IntegrationService(repo, audit, new PeakDetector(), clock, housekeeping);
        this.reviews = new ReviewService(repo, audit, keyring, housekeeping, clock);
        this.detector = new AnomalyDetector(repo, audit, reviews, ingest, clock);
        this.gatekeeper = new org.qclab.cds.review.ReleaseGatekeeper(repo, audit, reviews, ingest, detector);

        this.analyst = registerOperator("U-ANALYST", "李分析员", Role.ANALYST);
        this.reviewer = registerOperator("U-REVIEWER", "王复核员", Role.REVIEWER);
        this.qa = registerOperator("U-QA", "张质量保证", Role.QA);

        audit.record("system", "LAB_INITIALIZED", "system", "cds");
    }

    /** 真实时间。 */
    public static Lab create() {
        return new Lab(new InMemoryLabRepository(), AuditLedger.Clock.system());
    }

    /** 确定性时间：从给定时刻起每调用一次前进 1 分钟，便于演示诊断时间相关规则。 */
    public static Lab createDeterministic(Instant start) {
        return new Lab(new InMemoryLabRepository(), AuditLedger.Clock.ticking(start));
    }

    private Operator registerOperator(String id, String name, Role role) {
        keyring.register(id);
        String pub = java.util.Base64.getEncoder().encodeToString(
                keyring.publicKey(id).orElseThrow().getEncoded());
        Operator op = new Operator(id, name, role, pub);
        audit.record("system", "OPERATOR_REGISTERED", "operator", id,
                new TreeMap<>(java.util.Map.of("name", name, "role", role.name())));
        return op;
    }

    public Instrument instrument(String id, String vendor, String model, String serial) {
        Instrument ins = new Instrument(id, vendor, model, serial, CsvDemoAdapter.ADAPTER_ID);
        repo.saveInstrument(ins);
        audit.record("system", "INSTRUMENT_REGISTERED", "instrument", id,
                new TreeMap<>(java.util.Map.of("vendor", vendor, "model", model, "serial", serial)));
        return ins;
    }

    /**
     * 标准积分方法：用于演示的已批准方法。参数固定，保证积分可复现。
     */
    public IntegrationMethod method(int version, double mainRt, double slope) {
        TreeMap<String, Double> params = new TreeMap<>();
        params.put(IntegrationMethod.P_SLOPE_SENSITIVITY, slope);
        params.put(IntegrationMethod.P_MIN_HEIGHT, 0.5);
        params.put(IntegrationMethod.P_MIN_AREA, 0.5);
        params.put(IntegrationMethod.P_MAIN_RT, mainRt);
        params.put(IntegrationMethod.P_MAIN_RT_WINDOW, 0.3);
        params.put(IntegrationMethod.P_BASELINE_MODEL, 0.0);
        params.put(IntegrationMethod.P_PEAK_WIDTH, 0.02);
        params.put(IntegrationMethod.P_SHOULDER_DETECTION, 0.0);
        return new IntegrationMethod("METHOD-STD", version, "标准杂质方法", params,
                qa.operatorId(), clock.now());
    }

    /** 放宽斜率灵敏度的方法：正是"改积分参数"这个动作的载体。 */
    public IntegrationMethod relaxedMethod(int version, double mainRt) {
        IntegrationMethod strict = method(version, mainRt, 0.0);
        TreeMap<String, Double> params = new TreeMap<>(strict.parameters());
        // 抬高阈值，小峰不再被识别
        params.put(IntegrationMethod.P_MIN_HEIGHT, 20.0);
        params.put(IntegrationMethod.P_SLOPE_SENSITIVITY, 3.0);
        return new IntegrationMethod(strict.methodId(), version, "放宽阈值方法", params,
                qa.operatorId(), clock.now());
    }

    public Batch batch(String batchId, String productId, Specification spec, Instrument instrument) {
        Batch b = new Batch(batchId, productId, spec, instrument.instrumentId(), java.util.List.of(),
                analyst.operatorId(), clock.now(), Batch.BatchStatus.ACQUIRING);
        repo.saveBatch(b);
        audit.record(analyst.operatorId(), "BATCH_CREATED", "batch", batchId,
                new TreeMap<>(java.util.Map.of("productId", productId)));
        return b;
    }
}
