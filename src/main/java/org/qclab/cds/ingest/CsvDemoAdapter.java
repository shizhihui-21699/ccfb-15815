package org.qclab.cds.ingest;

import org.qclab.cds.domain.Chromatogram;

import java.nio.charset.StandardCharsets;

/**
 * 一个"厂商格式"的最小可运行适配器，用于演示与测试。
 *
 * <p>真实实现会解析 ANDI/AIA (netCDF)、Agilent .D、Waters .raw、Shimadzu .lcd 等；
 * 无论格式如何，都只需产出 {@link InstrumentAdapter.ParsedInjection}。文件格式：
 * <pre>
 * format=vendor-csv-v1
 * injection,1,S-001,2,SAMPLE,2026-01-01T09:00:00Z,0.0,0.5,1.0,...
 * </pre>
 * 一次性文件可包含多个 injection 行（模拟整段序列导出），
 * 适配器<b>全部返回</b>，不做任何取舍。
 */
public final class CsvDemoAdapter implements InstrumentAdapter {

    public static final String ADAPTER_ID = "vendor-csv-v1";

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public String vendorFormatName() {
        return "vendor-csv-v1";
    }

    @Override
    public IngestedRun parse(byte[] rawBytes, IngestContext context) {        String text = new String(rawBytes, StandardCharsets.UTF_8);
        java.util.List<ParsedInjection> out = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("format=") || t.startsWith("interval,")) {
                continue;
            }
            String[] f = t.split(",", -1);
            if (f.length < 10 || !"injection".equals(f[0])) {
                throw new IllegalArgumentException("无法识别的一行: " + t);
            }
            long seq = Long.parseLong(f[1]);
            String plannedKey = f[2];
            String sampleId = f[3];
            int runNo = Integer.parseInt(f[4]);
            var purpose = org.qclab.cds.domain.PlannedInjection.Purpose.valueOf(f[5]);
            var at = java.time.Instant.parse(f[6]);
            double start = Double.parseDouble(f[7]);
            double interval = Double.parseDouble(f[8]);
            double[] points = new double[f.length - 9];
            for (int i = 9; i < f.length; i++) {
                points[i - 9] = Double.parseDouble(f[i]);
            }
            out.add(new ParsedInjection(seq, plannedKey, sampleId, runNo, purpose, at,
                    new Chromatogram(start, interval, points), ""));
        }
        return new IngestedRun(out, vendorFormatName(),
                org.qclab.cds.crypto.Hashing.sha256Hex(rawBytes));
    }
}
