package org.qclab.cds.domain;

/** 角色。复核与放行必须由不同角色完成（职责分离）。 */
public enum Role {
    ANALYST,
    REVIEWER,
    QA;

    public boolean canReview() {
        return this == REVIEWER || this == QA;
    }

    public boolean canRelease() {
        return this == QA;
    }
}
