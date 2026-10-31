package jobapply.api;

import java.util.List;
import jobapply.memory.LeadGroups;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候选池的公司名 / 地点归并（C023-C）：页面把池子里出现的原文名发过来，返回原文 → 组名的映射。
 * 模型没配置或调用失败时照样 200，带上已有映射和 {@code error}，页面按原文分组并提示「重试」。
 */
@RestController
@RequestMapping("/api/lead-groups")
public class LeadGroupController {

    public record LeadGroupsRequest(List<String> companies, List<String> cities) {
    }

    private final LeadGroups groups;

    public LeadGroupController(LeadGroups groups) {
        this.groups = groups;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public LeadGroups.Groups resolve(@RequestBody LeadGroupsRequest request) {
        return groups.resolve(request.companies(), request.cities());
    }
}
