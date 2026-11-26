package jobapply.tools;

import jobapply.harness.policies.FieldIntent;

/**
 * 浏览器填写工具报告的一件事，由 {@code ApplicationStatePlugin} 自动记进申请状态（C019）和报告草稿：
 * 填了 / 核对上了、没填上的原因和见过的选项、交给用户亲填的 C 级字段、被拒绝的提交类按钮。
 * 工具只报告事实，不碰申请状态。
 */
public sealed interface FillEvent {

    /** 写操作成功：{@code before} 是操作之前的页面，{@code after} 是之后的，{@code notice} 是系统的补充说明（可能为 null）。 */
    record Filled(BrowserTools.Call call, PageSnapshot before, PageSnapshot after, String notice) implements FillEvent {
    }

    /** 填写工具失败（{@code message} 已遮值）：每个字段记下原因、见过的选项。 */
    record NotFilled(BrowserTools.Call call, PageSnapshot page, String message) implements FillEvent {
    }

    /** 这个字段没有发出（只读框、自定义下拉上键入）：{@code note} 记在字段上。 */
    record Skipped(FieldIntent field, PageSnapshot page, String note) implements FillEvent {
    }

    /** C 级字段交给用户亲填（{@code label} 是字段名，{@code pageTitle} 是所在页面）。 */
    record HandedOff(FieldIntent field, String label, String pageTitle) implements FillEvent {
    }

    /** 提交类按钮被拒绝（报告的守卫记录）。 */
    record Refused(String button) implements FillEvent {
    }
}
