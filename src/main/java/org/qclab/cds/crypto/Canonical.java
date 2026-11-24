package org.qclab.cds.crypto;

/**
 * 对象的规范形式。默认实现使用 {@link Json#canonical(Object)}，
 * 因此任何 record 只要字段类型可确定性序列化即自动获得稳定的内容哈希。
 */
public interface Canonical {

    default String canonicalForm() {
        return Json.canonical(this);
    }

    default String contentHash() {
        return Hashing.sha256Hex(canonicalForm());
    }
}
