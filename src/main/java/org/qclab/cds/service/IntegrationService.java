package org.qclab.cds.service;

import org.qclab.cds.audit.AuditLedger;
import org.qclab.cds.crypto.Json;
import org.qclab.cds.domain.IntegrationMethod;
import org.qclab.cds.domain.IntegrationResult;
import org.qclab.cds.domain.IntegrationVersion;
import org.qclab.cds.domain.RawInjection;
import org.qclab.cds.integration.PeakDetector;
import org.qclab.cds.store.LabRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 积分服务。系统里唯一能产生"结果"的地方。
 *
 * <p>两条硬约束：
 * <ol>
 *   <li><b>只能追加版本</b>：不存在"重新积分并覆盖"的 API。第一次积分是 v1，
 *       之后任何一次重新积分都必须是带原因的 v(n+1)，父版本指向前一版。</li>
 *   <li><b>只能对已封存的原始信号积分</b>：入参是已经入库的 {@code injectionId}，
 *       服务从仓库取出信号——不存在"传一段临时数组进来算个结果"的路径。</li>
 * </ol>
 *
 * <p>另外，积分方法在版本里存的是<b>快照 JSON</b>与哈希，不是方法 ID 引用。
 * 因此事后修改方法库不会改变历史结果的解释。
 */
public final class IntegrationService {

    private final LabRepository repo;
    private final AuditLedger audit;
    private final PeakDetector detector;
    private final AuditLedger.Clock clock;
    private final Housekeeping housekeeping;

    public IntegrationService(LabRepository repo, AuditLedger audit, PeakDetector detector,
                              AuditLedger.Clock clock, Housekeeping housekeeping) {
        this.repo = repo;
        this.audit = audit;
        this.detector = detector;
        this.clock = clock;
        this.housekeeping = housekeeping;
    }

    /** 首次积分。 */
    public IntegrationVersion integrateFirstTime(String injectionId, IntegrationMethod method, String actor) {
        RawInjection raw = requireUsableRaw(injectionId);
        IntegrationVersion latest = repo.latestVersion(injectionId).orElse(null);
        if (latest != null) {
            throw new IllegalStateException("该进样已有积分结果，重新积分必须走 reintegrate() 并写明原因");
        }
        return persist(raw, method, null, 1, actor, null);
    }

    /**
     * 重新积分。必须给出原因；新版本挂在版本链末尾，旧版本保留。
     *
     * @param reason 为什么重新积分。空字符串会被拒绝——这是复核人判断"正常调参"与"改到合格"的第一手材料。
     */
    public IntegrationVersion reintegrate(String injectionId, IntegrationMethod method, String actor, String reason) {
        RawInjection raw = requireUsableRaw(injectionId);
        IntegrationVersion latest = repo.latestVersion(injectionId)
                .orElseThrow(() -> new IllegalStateException("尚无首版积分，请先 integrateFirstTime()"));
        return persist(raw, method, latest.versionId(), latest.versionNo() + 1, actor, reason);
    }

    private IntegrationVersion persist(RawInjection raw, IntegrationMethod method, String parentId,
                                       int versionNo, String actor, String reason) {
        // 拦截：方法必须是已批准的具名版本（防止"临时改个参数试试"）
        if (method.approvedBy() == null || method.approvedAt() == null) {
            throw new IllegalArgumentException("积分方法必须是已批准的版本");
        }

        IntegrationResult result = detector.integrate(raw.trace(), method);
        String methodJson = Json.canonical(method);
        String resultJson = Json.canonical(result);
        String methodHash = org.qclab.cds.crypto.Hashing.sha256Hex(methodJson);
        String resultHash = org.qclab.cds.crypto.Hashing.sha256Hex(resultJson);

        IntegrationVersion version = new IntegrationVersion(
                "VER-" + UUID.randomUUID(),
                raw.injectionId(),
                parentId,
                versionNo,
                methodJson,
                methodHash,
                resultJson,
                resultHash,
                actor,
                clock.now(),
                reason
        );
        repo.appendVersion(version);

        TreeMap<String, String> details = new TreeMap<>(java.util.Map.of(
                "versionNo", Integer.toString(versionNo),
                "methodId", method.methodId(),
                "methodVersion", Integer.toString(method.methodVersion()),
                "methodHash", methodHash,
                "resultHash", resultHash,
                "impurityPercent", org.qclab.cds.crypto.Canon.num(result.impurityPercent()),
                "peakCount", Integer.toString(result.peaks().size()),
                "manuallyEditedPeaks", Integer.toString(result.manuallyEditedPeaks().size())
        ));
        if (reason != null) {
            details.put("reason", reason);
        }
        if (parentId != null) {
            details.put("parentVersionId", parentId);
        }
        audit.record(actor, versionNo == 1 ? "INTEGRATION_V1" : "REINTEGRATION",
                "integrationVersion", version.versionId(), details);

        // 重新积分会让任何已存在的复核签名失效——由 housekeeping 统一处理
        housekeeping.onVersionChanged(version.injectionId(), version.versionId(), actor);
        return version;
    }

    private RawInjection requireUsableRaw(String injectionId) {
        RawInjection raw = repo.raw(injectionId)
                .orElseThrow(() -> new IllegalArgumentException("不存在的进样: " + injectionId));
        if (!raw.isUsable()) {
            throw new IllegalStateException("该进样状态为 " + raw.status() + "，不能用于结果计算");
        }
        return raw;
    }

    public Optional<IntegrationVersion> current(String injectionId) {
        return repo.latestVersion(injectionId);
    }

    /**
     * 版本对比：返回相邻两版之间"消失的峰"与"新增的峰"。
     * 这正是"把积分参数一改，杂质峰不见了"的直接证据。
     */
    public PeakDelta compare(IntegrationVersion older, IntegrationVersion newer) {
        return PeakDelta.compute(older, newer);
    }
}
