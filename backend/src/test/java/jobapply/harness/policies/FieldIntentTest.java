package jobapply.harness.policies;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldIntentTest {

    @Test
    void takesTheExtraParametersOutAndLeavesTheRestForMcp() {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("target", "e3");
        field.put("name", "姓名");
        field.put("type", "textbox");
        field.put("value", "示例姓名");
        field.put("fact", "base.name");
        field.put("confidence", "high");
        field.put("language", "zh");

        FieldIntent intent = FieldIntent.from(field);
        Map<String, Object> rest = FieldIntent.strip(field);

        assertThat(intent).isEqualTo(new FieldIntent("e3", "姓名", "base.name", "high", "zh"));
        assertThat(intent.problem()).isNull();
        assertThat(intent.inferred()).isFalse();
        assertThat(rest).containsOnlyKeys("target", "name", "type", "value");
        assertThat(field).containsKey("fact"); // 原参数不变
    }

    @Test
    void usesElementAsTheLabelWhenThereIsNoName() {
        FieldIntent intent = FieldIntent.from(Map.of("target", "e2", "element", "邮箱", "text", "x",
                "fact", "none", "confidence", "low"));

        assertThat(intent.label()).isEqualTo("邮箱");
        assertThat(intent.inferred()).isTrue();
        assertThat(intent.language()).isNull();
        assertThat(intent.problem()).isNull();
    }

    @Test
    void factAndConfidenceAreOptionalOnlyTheLanguageIsChecked() {
        FieldIntent bare = FieldIntent.from(Map.of("target", "e2"));
        assertThat(bare.problem()).isNull();
        assertThat(bare.inferred()).isTrue();
        assertThat(FieldIntent.from(Map.of("fact", "base.name", "confidence", "high", "language", "fr")).problem())
                .contains("language");
        assertThat(FieldIntent.from(null)).isEqualTo(new FieldIntent(null, null, null, null, null));
    }
}
