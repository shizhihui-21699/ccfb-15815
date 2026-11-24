package org.qclab.cds.crypto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 规范化 JSON 序列化器——系统中所有哈希与签名的唯一输入格式。
 *
 * <p>确定性来自五点，缺一不可：
 * <ol>
 *   <li>字段按记录声明顺序输出（Java record 的规范顺序固定）</li>
 *   <li>{@link java.util.Map} 的键按字典序输出（domain 层一律用 {@code TreeMap}，见 {@link Canon#map}）</li>
 *   <li>浮点用 {@link Double#toString(double)} 的最短往返表示，与区域设置无关</li>
 *   <li>null 字段保留输出，避免"删掉字段"改变对象含义</li>
 *   <li><b>只有 record 组件参与序列化</b>：必须关闭 getter / is-getter / 字段自动发现。
 *       否则像 {@code isUsable()} 这样的辅助方法会被当成属性写进 JSON——
 *       它的改名或新增都会改变历史记录哈希，等于让"重构一下代码"变成"篡改历史"。</li>
 * </ol>
 *
 * <p><b>升级约束</b>：修改 Jackson 版本或此配置会改变历史记录的哈希，务必运行
 * {@code CanonicalizationGoldenTest} 中的固定哈希断言。生产环境还应把该测试的
 * 期望值归档为"格式基线"，升级前后比对。
 */
public final class Json {

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);

        // 序列化方向：只输出 record 组件。关闭 getter / is-getter / 字段三种自动发现，
        // 防止 isUsable() 之类辅助方法混入 JSON 并污染哈希。
        m.setVisibility(m.getSerializationConfig().getDefaultVisibilityChecker()
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withFieldVisibility(JsonAutoDetect.Visibility.NONE));
        // 反序列化方向：必须能从 JSON 字段还原 record 组件，因此单独设置可见性，
        // 不受上面阻塞 getter 的影响。
        m.setVisibility(m.getDeserializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE));
        return m;
    }

    private Json() {
    }

    public static String canonical(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法规范化序列化 " + o.getClass().getName(), e);
        }
    }

    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 " + type.getName(), e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
