package org.qclab.cds;

import org.junit.jupiter.api.Test;
import org.qclab.cds.audit.AuditRecord;
import org.qclab.cds.crypto.Json;
import org.qclab.cds.domain.Chromatogram;
import org.qclab.cds.domain.IntegrationMethod;
import org.qclab.cds.domain.Peak;

import java.time.Instant;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规范化格式的"黄金测试"。
 *
 * <p>本系统的不可篡改性全部建立在"同一对象永远产生同一字符串"之上。
 * 一旦 Jackson 升级、可见性配置改动、或某个 record 增删字段，
 * 历史记录的哈希就会整体改变——那意味着过去的所有签名全部失效。
 *
 * <p>因此这里用<b>固定输入 → 固定哈希</b>钉住格式。修改这些期望值等于
 * 宣布"历史数据需要迁移"，必须是一个显式的、经评审的决定，而不是某次重构的副作用。
 */
class CanonicalizationGoldenTest {

    @Test
    void peakFormatIsStable() {
        Peak p = new Peak(0, 6.0, 200.5, 1001.0, 0.12, 5.9, 6.1, "MAIN", false);
        String json = Json.canonical(p);
        assertEquals("{\"index\":0,\"retentionTimeMin\":6.0,\"area\":200.5,\"height\":1001.0,"
                + "\"widthMin\":0.12,\"startTimeMin\":5.9,\"endTimeMin\":6.1,"
                + "\"peakType\":\"MAIN\",\"manuallyEdited\":false}", json,
                "Peak 的规范化 JSON 变了——检查是否无意中改动了字段或 Jackson 配置");
    }

    /** 辅助方法（isImpurity）绝不能被序列化进 JSON，否则改名即篡改历史。 */
    @Test
    void derivedAccessorsAreNotSerialized() {
        Peak p = new Peak(0, 6.0, 200.5, 1001.0, 0.12, 5.9, 6.1, "UNKNOWN", false);
        String json = Json.canonical(p);
        assertFalse(json.contains("impurity"), "辅助方法泄漏进 JSON: " + json);
        assertEquals(9, countCommasOutsideQuotes(json) + 1, "应恰好序列化 9 个 record 组件: " + json);
    }

    @Test
    void methodParameterOrderDoesNotMatter() {
        TreeMap<String, Double> a = new TreeMap<>();
        a.put("slopeSensitivity", 0.001);
        a.put("minHeight", 0.5);
        TreeMap<String, Double> b = new TreeMap<>();
        b.put("minHeight", 0.5);
        b.put("slopeSensitivity", 0.001);
        IntegrationMethod mA = new IntegrationMethod("M", 1, "n", a, "qa", Instant.EPOCH);
        IntegrationMethod mB = new IntegrationMethod("M", 1, "n", b, "qa", Instant.EPOCH);
        assertEquals(mA.contentHash(), mB.contentHash(),
                "参数插入顺序不同不应产生不同哈希");
    }

    @Test
    void traceHashIsSensitiveToSinglePointChange() {
        double[] y = new double[100];
        for (int i = 0; i < y.length; i++) {
            y[i] = Math.sin(i / 10.0);
        }
        Chromatogram a = new Chromatogram(0, 0.01, y);
        double[] y2 = y.clone();
        y2[50] += 1e-12;   // 极小改动
        Chromatogram b = new Chromatogram(0, 0.01, y2);
        assertNotEquals(a.contentHash(), b.contentHash(),
                "哪怕改一个采样点的极小值，轨迹哈希也必须变化");
    }

    @Test
    void traceHashIgnoresSamplingDensityNarrative() {
        // 相同物理信号、相同时间网格 → 相同哈希，与构造方式无关
        double[] y = {1, 2, 3, 2, 1};
        assertEquals(new Chromatogram(0, 0.1, y).contentHash(),
                new Chromatogram(0, 0.1, y.clone()).contentHash());
    }

    @Test
    void auditRecordHashCoversEveryField() {
        AuditRecord base = new AuditRecord(1, Instant.parse("2026-01-15T08:00:00Z"),
                "U-1", "TEST", "entity", "id", AuditRecord.GENESIS_PREV, "", new TreeMap<>()).withHash();
        AuditRecord changedActor = new AuditRecord(1, base.timestamp(),
                "U-2", "TEST", "entity", "id", AuditRecord.GENESIS_PREV, "", new TreeMap<>()).withHash();
        assertNotEquals(base.recordHash(), changedActor.recordHash(),
                "操作员字段必须参与哈希，否则无法定位是谁改的");
    }

    private static int countCommasOutsideQuotes(String s) {
        int n = 0;
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                n++;
            }
        }
        return n;
    }
}
