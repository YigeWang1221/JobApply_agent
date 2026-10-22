package jobapply.domain;

import java.util.List;
import java.util.Locale;

/**
 * 岗位本身的描述，不含状态。
 */
public record JobPosting(
        String company,
        String recruitmentProject,
        String title,
        String jobId,
        String type,
        List<String> city,
        String url,
        String historyUrl,
        String site,
        String source) {

    public JobPosting {
        if (isBlank(company) || isBlank(title)) {
            throw new IllegalArgumentException("岗位必须有公司和岗位名");
        }
        if (isBlank(jobId) && isBlank(url)) {
            throw new IllegalArgumentException("岗位必须有官方岗位 ID 或链接，否则无法去重");
        }
        city = city == null ? List.of() : List.copyOf(city);
    }

    /**
     * 候选池去重键：公司 + 招聘项目 + 岗位 ID；没有岗位 ID 时用去掉查询参数和片段的 URL。
     */
    public String dedupeKey() {
        String identity = isBlank(jobId) ? stripQueryAndFragment(url.trim()) : normalize(jobId);
        return normalize(company) + "|" + normalize(recruitmentProject) + "|" + identity;
    }

    private static String stripQueryAndFragment(String value) {
        int cut = value.length();
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        if (fragment >= 0) {
            cut = Math.min(cut, fragment);
        }
        return value.substring(0, cut);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
