package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

/**
 * 一个被积分的峰。
 *
 * @param manuallyEdited 是否被手工增删/拖拽过。手工编辑本身合法，但必须单独标注，
 *                       复核人可以一键筛出全部手工编辑。
 * @param peakType       MAIN / IMPURITY / UNKNOWN / SYSTEM
 */
public record Peak(
        int index,
        double retentionTimeMin,
        double area,
        double height,
        double widthMin,
        double startTimeMin,
        double endTimeMin,
        String peakType,
        boolean manuallyEdited
) implements Canonical {

    public static final String MAIN = "MAIN";
    public static final String IMPURITY = "IMPURITY";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String SYSTEM = "SYSTEM";

    public boolean isImpurity() {
        return IMPURITY.equals(peakType) || UNKNOWN.equals(peakType);
    }
}
