package org.qclab.cds.service;

import org.qclab.cds.audit.AuditLedger;
import org.qclab.cds.audit.AuditRecord;
import org.qclab.cds.domain.Batch;
import org.qclab.cds.domain.Instrument;
import org.qclab.cds.domain.InjectionDisposition;
import org.qclab.cds.domain.PlannedInjection;
import org.qclab.cds.domain.RawInjection;
import org.qclab.cds.ingest.InstrumentAdapter;
import org.qclab.cds.store.InMemoryLabRepository;
import org.qclab.cds.store.LabRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 采集服务：把厂商导出物变成封存的原始进样，并与事先登记的计划对账。
 *
 * <p>这个服务对"删掉不合格进样"的反制是**结构性**的：
 * <ol>
 *   <li>计划先于采数登记（{@link #registerPlan}），因此"本来应该有这一针"是可查的事实；</li>
 *   <li>适配器返回几条就入库几条，服务不做任何挑选；</li>
 *   <li>对账时把"计划有、账本无"和"序号跳号"都暴露出来，而不是静默补齐。</li>
 * </ol>
 */
public final class IngestService {

    private final LabRepository repo;
    private final AuditLedger audit;
    private final java.util.Map<String, InstrumentAdapter> adapters = new java.util.HashMap<>();
    private final AuditLedger.Clock clock;
    /**
     * 处置变更后的回调。设为可选依赖而不是构造参数，以避免
     * IngestService ↔ Housekeeping 的循环依赖；由 {@link #bindHousekeeping} 注入。
     */
    private Housekeeping housekeeping;

    public IngestService(LabRepository repo, AuditLedger audit, AuditLedger.Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.clock = clock;
    }

    public void bindHousekeeping(Housekeeping housekeeping) {
        this.housekeeping = housekeeping;
    }

    public void registerAdapter(InstrumentAdapter adapter) {
        adapters.put(adapter.adapterId(), adapter);
    }

    public void registerPlan(Batch batch, List<PlannedInjection> planned) {
        for (PlannedInjection p : planned) {
            repo.addPlan(batch.batchId(), p);
            audit.record("system", "PLAN_REGISTERED", "plannedInjection", p.plannedKey(),
                    new TreeMap<>(java.util.Map.of(
                            "batchId", batch.batchId(),
                            "sampleId", p.sampleId(),
                            "runNumber", Integer.toString(p.runNumber()),
                            "purpose", p.purpose().name())));
        }
    }

    /**
     * 从厂商原始文件采入。返回本次封存的全部进样（未经筛选）。
     *
     * @throws IllegalStateException 当仪器未登记、适配器缺失或文件格式与适配器不符
     */
    public List<RawInjection> ingest(byte[] rawBytes, String instrumentId, String batchId,
                                     String acquiredBy, String plannedKeyHint) {
        Instrument instrument = repo.instrument(instrumentId)
                .orElseThrow(() -> new IllegalStateException("仪器未登记: " + instrumentId));
        InstrumentAdapter adapter = adapters.get(instrument.adapterId());
        if (adapter == null) {
            throw new IllegalStateException("缺少适配器: " + instrument.adapterId());
        }

        String payloadHash = org.qclab.cds.crypto.Hashing.sha256Hex(rawBytes);
        // 先记录"收到了一份原始文件"，即使后续解析失败也有迹可循
        audit.record(acquiredBy, "RAW_FILE_RECEIVED", "instrument", instrumentId,
                new TreeMap<>(java.util.Map.of(
                        "batchId", batchId,
                        "payloadHash", payloadHash,
                        "bytes", Integer.toString(rawBytes.length))));

        InstrumentAdapter.IngestedRun run = adapter.parse(rawBytes,
                new InstrumentAdapter.IngestContext(instrumentId, acquiredBy, plannedKeyHint));

        if (!run.vendorFormat().equals(adapter.vendorFormatName())) {
            throw new IllegalStateException("适配器格式不符: " + run.vendorFormat());
        }
        if (!payloadHash.equals(run.rawPayloadHash())) {
            // 适配器声称的哈希与调用方算的不一致：可能是实现 bug 或中间被改写
            audit.record(acquiredBy, "PAYLOAD_HASH_MISMATCH", "instrument", instrumentId,
                    new TreeMap<>(java.util.Map.of("expected", payloadHash, "claimed", run.rawPayloadHash())));
            throw new IllegalStateException("原始文件哈希不一致，拒绝入库");
        }

        List<RawInjection> sealed = new ArrayList<>();
        for (InstrumentAdapter.ParsedInjection p : run.injections()) {
            RawInjection raw = seal(p, instrumentId, acquiredBy, run.vendorFormat(), payloadHash);
            repo.appendRaw(raw);   // 重复 ID / 重复序号会抛出
            audit.record(acquiredBy, "INJECTION_SEALED", "rawInjection", raw.injectionId(),
                    new TreeMap<>(java.util.Map.of(
                            "sequenceNo", Long.toString(raw.sequenceNo()),
                            "plannedKey", String.valueOf(raw.plannedKey()),
                            "traceHash", raw.traceHash(),
                            "sampleId", String.valueOf(raw.sampleId()),
                            "status", raw.status().name())));
            sealed.add(raw);
        }

        // 注意：这里**不**逐次对账。序列尚在采集中时，"计划里有但还没进到"是正常状态，
        // 每次都报会制造大量重复的假缺口，把审计链淹没、把真缺口埋掉。
        // 缺口检测由 reconcile() 在序列结束时统一进行，并在开单时登记一条已报告记录去重。
        return sealed;
    }

    /**
     * 对该批次做一次对账，并把<b>新出现</b>的缺口写进审计。
     *
     * <p>去重很重要：同一缺口重复报警会让审计链充满噪声，复核人反而看不到真问题。
     * 因此每个缺口键只登记一次 PLAN_GAP_DETECTED；缺口一旦补齐，可以再报"缺口已闭合"。
     *
     * @return 对账结果
     */
    public Reconciliation reconcileAndReport(String batchId, String actor) {
        Reconciliation rec = reconcile(batchId);
        for (String missing : rec.missingPlannedKeys()) {
            reportGapOnce(batchId, actor, "MISSING_PLAN", missing);
        }
        for (Long gap : rec.sequenceGaps()) {
            reportGapOnce(batchId, actor, "SEQUENCE", Long.toString(gap));
        }
        return rec;
    }

    private final java.util.Set<String> reportedGaps = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void reportGapOnce(String batchId, String actor, String kind, String value) {
        String key = batchId + "|" + kind + "|" + value;
        if (!reportedGaps.add(key)) {
            return;
        }
        audit.record(actor, "PLAN_GAP_DETECTED", "batch", batchId,
                new TreeMap<>(java.util.Map.of("gapKind", kind, "gapValue", value)));
    }

    private RawInjection seal(InstrumentAdapter.ParsedInjection p, String instrumentId, String acquiredBy,
                              String vendorFormat, String payloadHash) {
        String injectionId = "INJ-" + UUID.nameUUIDFromBytes(
                (instrumentId + "|" + p.sequenceNo() + "|" + p.trace().contentHash())
                        .getBytes(StandardCharsets.UTF_8));
        return new RawInjection(
                injectionId,
                instrumentId,
                p.sequenceNo(),
                p.plannedKey(),
                p.sampleId(),
                p.runNumber(),
                p.purpose(),
                p.acquiredAt(),
                acquiredBy,
                RawInjection.RunStatus.COMPLETED,
                p.trace(),
                p.trace().contentHash(),
                payloadHash,
                vendorFormat,
                p.note()
        );
    }

    /** 声明某次进样无效（不删除）。 */
    public InjectionDisposition exclude(String injectionId, String reason, String actor) {
        RawInjection raw = repo.raw(injectionId)
                .orElseThrow(() -> new IllegalArgumentException("不存在的进样: " + injectionId));
        if (raw.status() == RawInjection.RunStatus.EXCLUDED_INVALID) {
            throw new IllegalStateException("该进样已作废");
        }
        InjectionDisposition d = new InjectionDisposition(
                "DSP-" + UUID.randomUUID(), injectionId, InjectionDisposition.Kind.EXCLUDE,
                reason, actor, clock.now());
        repo.addDisposition(d);
        audit.record(actor, "INJECTION_EXCLUDED", "rawInjection", injectionId,
                new TreeMap<>(java.util.Map.of("reason", reason, "dispositionId", d.dispositionId())));
        // 该进样已不能用于结果计算，它上面任何已完成的复核都失去依据 → 必须失效
        if (housekeeping != null) {
            housekeeping.onDispositionChanged(injectionId, actor,
                    "进样被声明无效（" + reason + "），其结果的复核依据不再成立");
        }
        return d;
    }

    /** 撤销作废。同样留下记录——"撤销了作废"本身也是要看的动作。 */
    public InjectionDisposition reinstate(String injectionId, String reason, String actor) {
        repo.raw(injectionId).orElseThrow(() -> new IllegalArgumentException("不存在的进样: " + injectionId));
        InjectionDisposition d = new InjectionDisposition(
                "DSP-" + UUID.randomUUID(), injectionId, InjectionDisposition.Kind.REINSTATE,
                reason, actor, clock.now());
        repo.addDisposition(d);
        audit.record(actor, "INJECTION_REINSTATED", "rawInjection", injectionId,
                new TreeMap<>(java.util.Map.of("reason", reason, "dispositionId", d.dispositionId())));
        // 恢复同样改变进样状态；原先的复核意见是针对"当时的作废状态"做的，
        // 不能自动复活，必须重新复核。
        if (housekeeping != null) {
            housekeeping.onDispositionChanged(injectionId, actor,
                    "进样作废被撤销（" + reason + "），需重新复核当前结果");
        }
        return d;
    }

    /** 进样的当前有效状态：优先看最后一个处置。 */
    public RawInjection.RunStatus effectiveStatus(String injectionId) {
        RawInjection raw = repo.raw(injectionId)
                .orElseThrow(() -> new IllegalArgumentException("不存在的进样: " + injectionId));
        RawInjection.RunStatus s = raw.status();
        for (InjectionDisposition d : repo.dispositionsFor(injectionId)) {
            s = d.kind() == InjectionDisposition.Kind.EXCLUDE
                    ? RawInjection.RunStatus.EXCLUDED_INVALID
                    : RawInjection.RunStatus.COMPLETED;
        }
        return s;
    }

    /** 批次对账：计划 vs 账本，以及仪器序号连续性。 */
    public Reconciliation reconcile(String batchId) {
        List<PlannedInjection> plans = repo.plansForBatch(batchId);
        List<RawInjection> raws = repo.rawsForBatch(batchId);

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RawInjection r : raws) {
            if (r.plannedKey() != null) {
                seen.add(r.plannedKey());
            }
        }
        List<String> missing = new ArrayList<>();
        for (PlannedInjection p : plans) {
            // REPEAT 是事后补的，不要求在计划中必然出现；其余类型应到必到
            if (p.purpose() != PlannedInjection.Purpose.REPEAT && !seen.contains(p.plannedKey())) {
                missing.add(p.plannedKey());
            }
        }

        List<Long> sequenceGaps = new ArrayList<>();
        long prev = 0;
        for (RawInjection r : raws) {
            if (prev != 0 && r.sequenceNo() != prev + 1) {
                for (long g = prev + 1; g < r.sequenceNo(); g++) {
                    sequenceGaps.add(g);
                }
            }
            prev = r.sequenceNo();
        }
        return new Reconciliation(batchId, plans.size(), raws.size(), missing, sequenceGaps);
    }

    public AuditLedger ledger() {
        return audit;
    }

    public Optional<AuditRecord> lastAudit() {
        return audit.last();
    }

    public InMemoryLabRepository repoAsMemory() {
        return (InMemoryLabRepository) repo;
    }

    /** 对账结果。 */
    public record Reconciliation(String batchId, int plannedCount, int sealedCount,
                                 List<String> missingPlannedKeys, List<Long> sequenceGaps) {
        public Reconciliation {
            missingPlannedKeys = List.copyOf(missingPlannedKeys);
            sequenceGaps = List.copyOf(sequenceGaps);
        }

        public boolean balanced() {
            return missingPlannedKeys.isEmpty() && sequenceGaps.isEmpty();
        }
    }
}
