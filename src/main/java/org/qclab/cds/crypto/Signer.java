package org.qclab.cds.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Ed25519 电子签名（21 CFR Part 11 §11.50 / §11.70：签名必须与记录绑定，
 * 且不能从记录上剥离后挪用到另一条记录）。
 *
 * <p>把签名绑定到"记录内容的哈希集合"上，是这个系统能抵御"先复核后改数据"的关键：
 * 记录一变，哈希集合就变，签名随即无法通过验证。
 */
public final class Signer {

    public static final String ALGORITHM = "Ed25519";

    private Signer() {
    }

    public static KeyPair generateKeyPair() {
        try {
            // Ed25519 的密钥长度由算法固定（256 位），不接受 initialize() 参数
            KeyPairGenerator g = KeyPairGenerator.getInstance(ALGORITHM);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM 缺少 Ed25519 支持（需要 JDK 15+）", e);
        }
    }

    public static String sign(PrivateKey key, String payload) {
        try {
            java.security.Signature sig = java.security.Signature.getInstance(ALGORITHM);
            sig.initSign(key);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    public static boolean verify(PublicKey key, String payload, String signatureB64) {
        if (key == null || payload == null || signatureB64 == null) {
            return false;
        }
        try {
            java.security.Signature sig = java.security.Signature.getInstance(ALGORITHM);
            sig.initVerify(key);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureB64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
