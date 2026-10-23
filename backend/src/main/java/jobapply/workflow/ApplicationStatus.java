package jobapply.workflow;

import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.persistence.AssetRegistry;

/**
 * 申请线程对外的状态（HTTP 接口原样返回，键是 camelCase）。没有内容的项为 null 或空列表。
 *
 * @param status       {@code running | waiting_user | ready | failed}
 * @param message      给用户看的话：要用户做什么、做完怎么回复（{@code waiting_user}、{@code ready} 时有）
 * @param questions    要用户回答的问题（只有页面原文和选项）；{@code fieldClass} 为 C 的只能在浏览器里亲自填
 * @param fileSlots    要用户选文件的上传位置
 * @param assets       有上传位置时可选的原件（同 {@code GET /api/assets}）
 * @param disconnected 浏览器断开了，用户回复时系统先重连
 * @param plan         模型的计划和进度
 * @param report       填写报告：{@code draft | final}；还没有时为 null（正文用 {@code GET .../report}）
 * @param error        失败的原因
 */
public record ApplicationStatus(
        String job,
        String project,
        String session,
        String status,
        String message,
        List<AgentRun.Question> questions,
        List<AgentRun.FileSlot> fileSlots,
        List<AssetRegistry.Asset> assets,
        boolean disconnected,
        List<AgentRun.PlanItem> plan,
        AgentRun.Usage usage,
        String prepState,
        String report,
        int round,
        String error) {
}
