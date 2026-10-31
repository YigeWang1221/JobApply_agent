package jobapply.workflow;

import java.util.List;
import jobapply.domain.AgentRun;
import jobapply.domain.ApplicationState;
import jobapply.persistence.AssetRegistry;

/**
 * 申请线程对外的状态（HTTP 接口原样返回，键是 camelCase）。没有内容的项为 null 或空列表。
 *
 * @param status       {@code queued | running | waiting_user | ready | failed | interrupted | done}：排队和在跑只看内存；
 *                     {@code done} 是用户已经定下了（已提交、在提交、结束了），
 *                     <b>投递段</b>的申请线程结束、不能再回复——不是申请结束（申请的阶段看 Job 的 phase，C024-fix）；
 *                     {@code interrupted} 是应用重启前还在跑的（{@code error} 写着，回复后接着做）
 * @param message      给用户看的话：要用户做什么、做完怎么回复（{@code waiting_user}、{@code ready} 时有）
 * @param questions    要用户回答的问题（只有页面原文和选项）；{@code fieldClass} 为 C 的只能在浏览器里亲自填
 * @param fileSlots    要用户选文件的上传位置
 * @param assets       有上传位置时可选的原件（同 {@code GET /api/assets}）
 * @param attachments  这个申请已经上传的附件（上传位置也可以选它们：{@code files} 里写 {@code attachment:<id>}）
 * @param disconnected 在等你（或中断了），而浏览器现在断着：从全局的连接状态推出来（C006-fix-browser 第 1 节），
 *                     回复或者点顶部的「重新连接」时再连
 * @param state        申请状态（C019）：现在在哪一步、计划、字段清单和每个字段的状态；工作台侧栏的「进度」
 * @param report       填写报告：{@code draft | final}；还没有时为 null（正文用 {@code GET .../report}）
 * @param error        失败的原因
 * @param inserted     运行中插了、Agent 还没看到的话（C010）
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
        List<Attachment> attachments,
        boolean disconnected,
        ApplicationState state,
        AgentRun.Usage usage,
        String prepState,
        String report,
        int round,
        String error,
        List<String> inserted) {

    /** 一个附件；{@code url} 读文件（图片可以直接显示）。 */
    public record Attachment(String id, String name, String contentType, boolean image, String url) {
    }
}
