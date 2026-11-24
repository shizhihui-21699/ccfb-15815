package org.qclab.cds.crypto;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作员的签名密钥。生产环境应使用 HSM 或智能卡，私钥永不离开硬件；
 * 此处以进程内密钥对模拟，但签名/验签路径与生产一致。
 */
public final class Keyring {

    private final Map<String, KeyPair> keys = new ConcurrentHashMap<>();

    public void register(String operatorId) {
        keys.putIfAbsent(operatorId, Signer.generateKeyPair());
    }

    public Optional<PublicKey> publicKey(String operatorId) {
        KeyPair kp = keys.get(operatorId);
        return Optional.ofNullable(kp).map(KeyPair::getPublic);
    }

    public PrivateKey privateKey(String operatorId) {
        KeyPair kp = keys.get(operatorId);
        if (kp == null) {
            throw new IllegalStateException("操作员未注册签名密钥: " + operatorId);
        }
        return kp.getPrivate();
    }
}
