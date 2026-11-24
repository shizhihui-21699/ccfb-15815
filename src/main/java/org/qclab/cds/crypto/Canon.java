package org.qclab.cds.crypto;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 规范字符串的拼装工具。所有分隔符都做转义，避免字段内容伪造结构。 */
public final class Canon {

    private Canon() {
    }

    /**
     * 数字一律用 {@link Double#toString(double)}：它是最短且可精确往返的表示，
     * 与区域设置无关，也不会因为 1.0 与 1.00 的差别产生不同哈希。
     */
    public static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("非有限浮点数不能进入规范形式: " + d);
        }
        return Double.toString(d == 0.0d ? 0.0d : d); // 归一化 -0.0
    }

    public static String num(long v) {
        return Long.toString(v);
    }

    public static String bool(boolean b) {
        return b ? "1" : "0";
    }

    /** 可空字符串：null 与空串必须可区分，否则能通过删字段伪造内容。 */
    public static String s(String v) {
        return v == null ? "~null~" : "=" + esc(v);
    }

    public static String instant(java.time.Instant v) {
        return v == null ? "~null~" : v.toString();
    }

    /** 由交替的 key/value 参数构造 {@code k1=v1;k2=v2}。 */
    public static String kv(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("kv() 需要成对的 key/value");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(esc(pairs[i])).append('=').append(esc(pairs[i + 1]));
        }
        return sb.toString();
    }

    /** 映射按键排序后展开，保证与插入顺序无关。 */
    public static String map(Map<String, ?> m) {
        if (m == null || m.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        m.forEach((k, v) -> sorted.put(k, v == null ? "~null~" : String.valueOf(v)));
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(esc(k)).append('=').append(esc(v));
        });
        return sb.toString();
    }

    public static String list(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(esc(items.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String esc(String v) {
        if (v == null) {
            return "~null~";
        }
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case ';' -> sb.append("\\;");
                case '=' -> sb.append("\\=");
                case '|' -> sb.append("\\|");
                case ',' -> sb.append("\\,");
                case '[' -> sb.append("\\[");
                case ']' -> sb.append("\\]");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
