package org.qclab.cds.review;

import org.qclab.cds.crypto.Canonical;

import java.util.List;

/**
 * 放行门槛的判定结果：一组<b>必须逐条满足</b>的条件。
 *
 * <p>与 {@link ReviewScreening} 的分工：
 * <ul>
 *   <li>筛查回答"<b>哪些记录可疑</b>"——它是排序工具，用于把复核人的注意力引到少数条目上；</li>
 *   <li>门槛回答"<b>这一批现在能不能放</b>"——它是布尔判定，任何一条不满足即拒绝。</li>
 * </ul>
 * 早期版本把两者混为一谈，导致"队列里尚未复核"这种正常状态被当成告警，
 * 使 blocking 恒为真、告警失去区分度。分开之后，<b>可疑</b>与<b>未完成</b>
 * 各归各的机制：前者要求人判断，后者要求人做事。
 */
public record ReleaseGate(
        String batchId,
        boolean releasable,
        List<Check> checks
) implements Canonical {

    /**
     * 单项检查。
     *
     * @param passed 是否满足
     * @param blocking 未满足时是否阻断放行（有些条件是提示性的）
     */
    public record Check(String code, String description, boolean passed, boolean blocking, String detail)
            implements Canonical {
        public static Check pass(String code, String description, String detail) {
            return new Check(code, description, true, true, detail);
        }

        /** 不阻断的提示项：即使不满足也不影响可放行性。 */
        public static Check advisory(String code, String description, boolean passed, String detail) {
            return new Check(code, description, passed, false, detail);
        }

        public static Check fail(String code, String description, String detail) {
            return new Check(code, description, false, true, detail);
        }
    }

    public ReleaseGate {
        checks = List.copyOf(checks);
    }

    public List<Check> failed() {
        return checks.stream().filter(c -> !c.passed() && c.blocking()).toList();
    }

    public List<Check> advisories() {
        return checks.stream().filter(c -> !c.blocking()).toList();
    }

    /**
     * 判定资格的语义摘要。
     *
     * <p>刻意<b>不</b>用 {@code checks.equals()} 做"两次评估是否一致"的比较：
     * Check 的 {@code detail} 里含"已校验 N 条审计记录"这类会随每次评估自身写审计而变化的值。
     * 用摘要比较的是"哪些条件通过/阻断"这一真正决定放行资格的语义，
     * 而不是会自然漂移的呈现细节。
     */
    public String eligibilityDigest() {
        StringBuilder sb = new StringBuilder();
        for (Check c : checks) {
            sb.append(c.code()).append('=')
                    .append(c.passed() ? "P" : (c.blocking() ? "B" : "a")).append(';');
        }
        sb.append("releasable=").append(releasable);
        return sb.toString();
    }

    /** 供界面直接渲染的清单。 */
    public String toChecklist() {
        StringBuilder sb = new StringBuilder();
        sb.append(releasable ? "✔ 可以放行\n" : "✘ 不可放行\n");
        for (Check c : checks) {
            sb.append(c.passed() ? "  [通过] " : (c.blocking() ? "  [阻断] " : "  [提示] "))
                    .append(c.description());
            if (c.detail() != null && !c.detail().isBlank()) {
                sb.append(" — ").append(c.detail());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
