package jobapply.api;

import jobapply.memory.ExperienceIndex;
import jobapply.memory.ExperienceModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设置页「经验检索」（C022）：本地嵌入模型的状态（{@code absent / downloading / ready / failed}）、下载进度（0–1），
 * 以及手动开始下载（失败后重试也用它）。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final ExperienceModel model;
    private final ExperienceIndex index;

    public RagController(ExperienceModel model, ExperienceIndex index) {
        this.model = model;
        this.index = index;
    }

    @GetMapping("/status")
    public ExperienceModel.Status status() {
        return withIndex(model.status());
    }

    @PostMapping("/download")
    public ExperienceModel.Status download() {
        return withIndex(model.download());
    }

    /** 模型好了以后，message 里再说一句索引的情况（更新索引出错时把原因带上）。 */
    private ExperienceModel.Status withIndex(ExperienceModel.Status status) {
        if (!ExperienceModel.State.READY.value().equals(status.state())) {
            return status;
        }
        String note = index.lastError() != null ? "更新索引出错：" + index.lastError()
                : index.synced() ? "索引已和记录对上" : "索引会在后台建好";
        return new ExperienceModel.Status(status.state(), status.progress(), status.message() + "；" + note);
    }
}
