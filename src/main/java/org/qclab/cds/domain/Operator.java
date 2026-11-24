package org.qclab.cds.domain;

import org.qclab.cds.crypto.Canonical;

public record Operator(String operatorId, String displayName, Role role, String publicKeyB64)
        implements Canonical {
}
