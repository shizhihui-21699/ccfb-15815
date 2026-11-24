package org.qclab.cds.demo;

import org.qclab.cds.domain.Chromatogram;
import org.qclab.cds.domain.PlannedInjection;

import java.time.Instant;

/**
 * 把合成信号打包成"厂商导出文件"字节流，交给 {@link org.qclab.cds.ingest.CsvDemoAdapter} 解析。
 * 演示与端到端测试用；真实环境里这份字节就是仪器导出的 AIA/.D 文件。
 */
public final class VendorFileWriter {

    private final StringBuilder sb = new StringBuilder();

    public VendorFileWriter() {
        sb.append("format=vendor-csv-v1\n");
        sb.append("# 由仪器软件导出；本系统只读取，不改写\n");
    }

    public VendorFileWriter injection(long sequenceNo, String plannedKey, String sampleId, int runNumber,
                                      PlannedInjection.Purpose purpose, Instant acquiredAt,
                                      Chromatogram trace) {
        sb.append("injection,").append(sequenceNo)
                .append(',').append(plannedKey)
                .append(',').append(sampleId)
                .append(',').append(runNumber)
                .append(',').append(purpose.name())
                .append(',').append(acquiredAt)
                .append(',').append(trace.startTimeMin())
                .append(',').append(trace.intervalMin());
        double[] y = trace.intensities();
        for (double v : y) {
            sb.append(',').append(v);
        }
        sb.append('\n');
        return this;
    }

    public byte[] bytes() {
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
