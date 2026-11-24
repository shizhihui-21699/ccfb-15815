package org.qclab.cds.store;

import org.qclab.cds.domain.Batch;
import org.qclab.cds.domain.InjectionDisposition;
import org.qclab.cds.domain.Instrument;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.domain.PlannedInjection;
import org.qclab.cds.domain.RawInjection;
import org.qclab.cds.domain.ReviewSignature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现。所有集合都保留完整历史（签名、版本、处置都是追加），
 * 读取时再按需挑选"当前有效"的那一条。
 */
public final class InMemoryLabRepository implements LabRepository {

    private final Map<String, Instrument> instruments = new ConcurrentHashMap<>();
    private final Map<String, Batch> batches = new ConcurrentHashMap<>();
    private final Map<String, List<PlannedInjection>> plans = new ConcurrentHashMap<>();
    private final Map<String, RawInjection> raws = new ConcurrentHashMap<>();
    private final Map<String, List<InjectionDisposition>> dispositions = new ConcurrentHashMap<>();
    private final Map<String, List<IntegrationVersion>> versions = new ConcurrentHashMap<>();
    private final Map<String, List<ReviewSignature>> signatures = new ConcurrentHashMap<>();
    private final Map<String, List<org.qclab.cds.review.FindingDisposition>> explanations = new ConcurrentHashMap<>();

    private final java.util.Set<Long> usedSequences = ConcurrentHashMap.newKeySet();

    @Override
    public void saveInstrument(Instrument instrument) {
        instruments.put(instrument.instrumentId(), instrument);
    }

    @Override
    public Optional<Instrument> instrument(String instrumentId) {
        return Optional.ofNullable(instruments.get(instrumentId));
    }

    @Override
    public void saveBatch(Batch batch) {
        batches.put(batch.batchId(), batch);
    }

    @Override
    public Optional<Batch> batch(String batchId) {
        return Optional.ofNullable(batches.get(batchId));
    }

    @Override
    public void addPlan(String batchId, PlannedInjection planned) {
        plans.computeIfAbsent(batchId, k -> new ArrayList<>()).add(planned);
    }

    @Override
    public List<PlannedInjection> plansForBatch(String batchId) {
        return List.copyOf(plans.getOrDefault(batchId, List.of()));
    }

    @Override
    public synchronized void appendRaw(RawInjection raw) {
        if (raws.containsKey(raw.injectionId())) {
            throw new DuplicateRawException("进样 " + raw.injectionId() + " 已封存，不得覆盖");
        }
        if (!usedSequences.add(raw.sequenceNo())) {
            throw new DuplicateRawException("序号 " + raw.sequenceNo() + " 已被占用，可能是厂商侧序号被重排");
        }
        raws.put(raw.injectionId(), raw);
    }

    @Override
    public Optional<RawInjection> raw(String injectionId) {
        return Optional.ofNullable(raws.get(injectionId));
    }

    @Override
    public List<RawInjection> rawsForBatch(String batchId) {
        // 进样按计划 key 归属批次：计划 key 的格式为 "<batchId>#<n>"
        List<RawInjection> out = new ArrayList<>();
        for (RawInjection r : raws.values()) {
            if (r.plannedKey() != null && r.plannedKey().startsWith(batchId + "#")) {
                out.add(r);
            }
        }
        out.sort((a, b) -> Long.compare(a.sequenceNo(), b.sequenceNo()));
        return out;
    }

    @Override
    public void addDisposition(InjectionDisposition disposition) {
        dispositions.computeIfAbsent(disposition.injectionId(), k -> new ArrayList<>()).add(disposition);
    }

    @Override
    public List<InjectionDisposition> dispositionsFor(String injectionId) {
        List<InjectionDisposition> list = new ArrayList<>(dispositions.getOrDefault(injectionId, List.of()));
        // 按时间显式排序：派生"当前状态"必须不依赖存储的返回顺序。
        // 内存实现恰好按插入顺序，但落库后查询计划可能改变顺序，届时会静默改变结论。
        list.sort((a, b) -> a.at().compareTo(b.at()));
        return list;
    }

    @Override
    public void appendVersion(IntegrationVersion version) {
        versions.computeIfAbsent(version.injectionId(), k -> new ArrayList<>()).add(version);
    }

    @Override
    public Optional<IntegrationVersion> version(String versionId) {
        return versions.values().stream()
                .flatMap(List::stream)
                .filter(v -> v.versionId().equals(versionId))
                .findFirst();
    }

    @Override
    public List<IntegrationVersion> versionsForInjection(String injectionId) {
        List<IntegrationVersion> list = new ArrayList<>(versions.getOrDefault(injectionId, List.of()));
        list.sort((a, b) -> Integer.compare(a.versionNo(), b.versionNo()));
        return list;
    }

    @Override
    public List<IntegrationVersion> versionsForBatch(String batchId) {
        List<IntegrationVersion> out = new ArrayList<>();
        for (RawInjection r : rawsForBatch(batchId)) {
            out.addAll(versionsForInjection(r.injectionId()));
        }
        return out;
    }

    @Override
    public Optional<IntegrationVersion> latestVersion(String injectionId) {
        List<IntegrationVersion> list = versionsForInjection(injectionId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    @Override
    public void appendSignature(ReviewSignature signature) {
        // 签名表以 versionId 为键：同一对象的新状态（如失效）作为追加行保留，读取时取最后一条
        signatures.computeIfAbsent(signature.versionId(), k -> new ArrayList<>()).add(signature);
    }

    @Override
    public List<ReviewSignature> signaturesForVersion(String versionId) {
        return List.copyOf(signatures.getOrDefault(versionId, List.of()));
    }

    @Override
    public List<ReviewSignature> signaturesForBatch(String batchId) {
        List<ReviewSignature> out = new ArrayList<>();
        for (IntegrationVersion v : versionsForBatch(batchId)) {
            out.addAll(signaturesForVersion(v.versionId()));
        }
        return out;
    }

    @Override
    public List<ReviewSignature> signaturesForBatchVersionsOf(String injectionId) {
        List<ReviewSignature> out = new ArrayList<>();
        for (IntegrationVersion v : versionsForInjection(injectionId)) {
            out.addAll(signaturesForVersion(v.versionId()));
        }
        return out;
    }

    @Override
    public List<ReviewSignature> allSignatures() {
        List<ReviewSignature> out = new ArrayList<>();
        signatures.values().forEach(out::addAll);
        return out;
    }

    @Override
    public void addExplanation(org.qclab.cds.review.FindingDisposition disposition) {
        explanations.computeIfAbsent(disposition.findingKey(), k -> new ArrayList<>()).add(disposition);
    }

    @Override
    public List<org.qclab.cds.review.FindingDisposition> explanationsFor(String findingKey) {
        return List.copyOf(explanations.getOrDefault(findingKey, List.of()));
    }

    @Override
    public List<org.qclab.cds.review.FindingDisposition> allExplanations() {
        List<org.qclab.cds.review.FindingDisposition> out = new ArrayList<>();
        explanations.values().forEach(out::addAll);
        return out;
    }

    /** 序号冲突时抛出，上层会转成审计里的 CRITICAL 发现而非静默吞掉。 */
    public static final class DuplicateRawException extends RuntimeException {
        public DuplicateRawException(String message) {
            super(message);
        }
    }

    /** 供测试与展示：全部原始进样。 */
    public List<RawInjection> allRaws() {
        List<RawInjection> list = new ArrayList<>(raws.values());
        list.sort((a, b) -> Long.compare(a.sequenceNo(), b.sequenceNo()));
        return list;
    }

    /** 空实现之外的便捷构造：按插入顺序之子集查询。 */
    public Map<String, List<IntegrationVersion>> versionIndex() {
        return new LinkedHashMap<>(versions);
    }
}
