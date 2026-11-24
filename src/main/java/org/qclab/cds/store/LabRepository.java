package org.qclab.cds.store;

import org.qclab.cds.domain.Batch;
import org.qclab.cds.domain.InjectionDisposition;
import org.qclab.cds.domain.Instrument;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.domain.PlannedInjection;
import org.qclab.cds.domain.RawInjection;
import org.qclab.cds.domain.ReviewSignature;

import java.util.List;
import java.util.Optional;

/**
 * 业务数据仓库接口。
 *
 * <p>注意这里的"所有写入都是追加"：
 * <ul>
 *   <li>原始进样一旦入库，接口上没有更替方法，只有 {@code addDisposition} 追加处置；</li>
 *   <li>积分只追加版本；</li>
 *   <li>复核只追加签名 / 失效记录。</li>
 * </ul>
 * 状态字段（例如签名失效）在内存实现中由"派生视图"计算，落库时用新行 + 旧行保留的方式，
 * 绝不原地 UPDATE 掉历史。
 */
public interface LabRepository {

    void saveInstrument(Instrument instrument);

    Optional<Instrument> instrument(String instrumentId);

    void saveBatch(Batch batch);

    Optional<Batch> batch(String batchId);

    void addPlan(String batchId, PlannedInjection planned);

    List<PlannedInjection> plansForBatch(String batchId);

    /** 追加一条封存的原始进样。重复 ID 或重复序号将被拒绝。 */
    void appendRaw(RawInjection raw);

    Optional<RawInjection> raw(String injectionId);

    List<RawInjection> rawsForBatch(String batchId);

    void addDisposition(InjectionDisposition disposition);

    List<InjectionDisposition> dispositionsFor(String injectionId);

    void appendVersion(IntegrationVersion version);

    Optional<IntegrationVersion> version(String versionId);

    List<IntegrationVersion> versionsForInjection(String injectionId);

    List<IntegrationVersion> versionsForBatch(String batchId);

    /** 当前（最新）版本。 */
    Optional<IntegrationVersion> latestVersion(String injectionId);

    void appendSignature(ReviewSignature signature);

    List<ReviewSignature> signaturesForVersion(String versionId);

    List<ReviewSignature> signaturesForBatch(String batchId);

    /** 某进样所有版本上的全部签名（含历史版本），供失效看护使用。 */
    List<ReviewSignature> signaturesForBatchVersionsOf(String injectionId);

    /** 全库签名，供定时失效扫描使用。 */
    List<ReviewSignature> allSignatures();

    void addExplanation(org.qclab.cds.review.FindingDisposition disposition);

    /** 按 {@link org.qclab.cds.review.FindingDisposition#keyOf} 取该发现的历史处置。 */
    List<org.qclab.cds.review.FindingDisposition> explanationsFor(String findingKey);

    List<org.qclab.cds.review.FindingDisposition> allExplanations();
}
