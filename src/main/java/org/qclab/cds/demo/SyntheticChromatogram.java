package org.qclab.cds.demo;

import org.qclab.cds.domain.Chromatogram;

/**
 * 合成色谱图生成器（仅用于演示与测试，不进入生产逻辑）。
 *
 * <p>用高斯峰叠加生成可预测的信号：每个峰由保留时间、峰高、峰宽描述。
 * 这样测试可以精确构造"有杂质峰"与"没有杂质峰"两种信号，
 * 从而验证"重新积分让峰消失"能被检出。
 */
public final class SyntheticChromatogram {

    public record PeakSpec(double rtMin, double height, double sigmaMin) {
    }

    private double start = 0.0;
    private double interval = 0.01;
    private double duration = 20.0;
    private double baseline = 1.0;
    private double noise = 0.0;
    private long noiseSeed = 42L;

    public static SyntheticChromatogram create() {
        return new SyntheticChromatogram();
    }

    public SyntheticChromatogram start(double v) {
        this.start = v;
        return this;
    }

    public SyntheticChromatogram interval(double v) {
        this.interval = v;
        return this;
    }

    public SyntheticChromatogram duration(double v) {
        this.duration = v;
        return this;
    }

    public SyntheticChromatogram baseline(double v) {
        this.baseline = v;
        return this;
    }

    public SyntheticChromatogram noise(double amplitude, long seed) {
        this.noise = amplitude;
        this.noiseSeed = seed;
        return this;
    }

    public Chromatogram build(PeakSpec... peaks) {
        int n = (int) Math.round(duration / interval) + 1;
        double[] y = new double[n];
        java.util.Random rnd = new java.util.Random(noiseSeed);
        for (int i = 0; i < n; i++) {
            double t = start + interval * i;
            double v = baseline;
            for (PeakSpec p : peaks) {
                double d = t - p.rtMin();
                v += p.height() * Math.exp(-(d * d) / (2 * p.sigmaMin() * p.sigmaMin()));
            }
            if (noise > 0) {
                v += (rnd.nextDouble() - 0.5) * 2 * noise;
            }
            y[i] = v;
        }
        return new Chromatogram(start, interval, y);
    }
}
