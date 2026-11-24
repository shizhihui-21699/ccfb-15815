package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;
import org.qclab.cds.crypto.Hashing;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 色谱信号轨迹：等间隔采样的时间/强度数组。
 *
 * <p>哈希按<b>定长二进制</b>编码计算（而非 JSON），原因有二：
 * <ul>
 *   <li>一张 60 分钟、10 Hz 的色谱图有 36000 个点，JSON 会让哈希计算成为瓶颈</li>
 *   <li>IEEE-754 双精度的字节表示是精确的，不存在十进制往返的歧义</li>
 * </ul>
 * 编码为：魔数 + 点数 + 采样间隔 + 逐点 (time, intensity)，全部小端。
 * 任何一位改变都会改变哈希，因此"改掉一个峰高"与"删掉整个文件"同样可被检出。
 */
public record Chromatogram(double startTimeMin, double intervalMin, double[] intensities)
        implements Canonical {

    private static final byte[] MAGIC = {'Q', 'C', 'D', 'S', 'G', '0', '1'};

    public Chromatogram {
        if (intervalMin <= 0) {
            throw new IllegalArgumentException("采样间隔必须为正");
        }
        if (intensities == null || intensities.length == 0) {
            throw new IllegalArgumentException("色谱信号不能为空");
        }
        intensities = intensities.clone();
    }

    @Override
    public double[] intensities() {
        return intensities.clone();
    }

    public int pointCount() {
        return intensities.length;
    }

    public double endTimeMin() {
        return startTimeMin + intervalMin * (intensities.length - 1);
    }

    public double timeAt(int index) {
        return startTimeMin + intervalMin * index;
    }

    /**
     * 轨迹哈希。刻意<b>不</b>使用 {@link Canonical#canonicalForm()} 的 JSON 路径，
     * 因为 JSON 里 double 的最短表示在极大/极小值上依赖实现细节，二进制更稳。
     */
    @Override
    public String contentHash() {
        byte[] encoded = encode();
        return Hashing.sha256Hex(encoded);
    }

    @Override
    public String canonicalForm() {
        // 规范形式用于日志与人类可读比对，不用于哈希。
        return "chromatogram[start=" + startTimeMin + ",interval=" + intervalMin
                + ",points=" + intensities.length + ",hash=" + contentHash() + "]";
    }

    private byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(MAGIC.length + 24 + intensities.length * 16);
        out.writeBytes(MAGIC);
        writeLong(out, intensities.length);
        writeDouble(out, startTimeMin);
        writeDouble(out, intervalMin);
        for (int i = 0; i < intensities.length; i++) {
            writeDouble(out, startTimeMin + intervalMin * i);
            writeDouble(out, intensities[i]);
        }
        return out.toByteArray();
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        out.writeBytes(ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array());
    }

    private static void writeDouble(ByteArrayOutputStream out, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("信号点含非有限值");
        }
        out.writeBytes(ByteBuffer.allocate(Double.BYTES).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Chromatogram c
                && Double.compare(c.startTimeMin, startTimeMin) == 0
                && Double.compare(c.intervalMin, intervalMin) == 0
                && Arrays.equals(c.intensities, intensities);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Double.hashCode(startTimeMin) + Double.hashCode(intervalMin))
                + Arrays.hashCode(intensities);
    }
}
