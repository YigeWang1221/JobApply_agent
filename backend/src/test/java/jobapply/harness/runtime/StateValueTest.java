package jobapply.harness.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 申请状态（C019）里 C 级字段只记占位符：真实值不能进运行记录和报告（dev-limit「C 级占位符」）。 */
class StateValueTest {

    @Test
    void classCKeepsOnlyPlaceholders() {
        assertThat(HarnessLoop.storable("证件号码", "FAKE-0000")).isNull();
        assertThat(HarnessLoop.storable("证件号码", "{ID_NUMBER}")).isEqualTo("{ID_NUMBER}");
        assertThat(HarnessLoop.storable("学校名称", "示例大学")).isEqualTo("示例大学");
    }
}
