package jobapply.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次资料导入（{@code base/import-runs/<id>.json}）。C004-A-1 只建到 {@code estimated}（含估算）；
 * 之后的 {@code running} → {@code reviewing} → {@code done} / {@code failed}、进度和候选在 C004-A-2。不放原文。
 *
 * @param extra 未识别的字段（C004-A-2 加的进度、候选等），读写时原样保留
 */
public record ImportRun(String id, Status status, String createdAt, String updatedAt, List<String> sources,
                        Estimate estimate, Map<String, Object> extra) {

    public enum Status {
        ESTIMATED, RUNNING, REVIEWING, DONE, FAILED;

        public String code() {
            return name().toLowerCase();
        }

        public static Status of(String code) {
            for (Status status : values()) {
                if (status.code().equals(code)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("不认识的导入状态：" + code);
        }
    }

    /**
     * @param githubCallsUsed    估算时已经用掉的 GitHub 调用
     * @param githubCallsPlanned 开始整理以后还要用的 GitHub 调用
     * @param overLimit          有来源超过上限，需要缩小范围
     */
    public record Estimate(List<SourceEstimate> sources, long chars, int modelCalls, int githubCallsUsed,
                           int githubCallsPlanned, List<String> warnings, boolean overLimit) {

        public Estimate {
            sources = sources == null ? List.of() : List.copyOf(sources);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record SourceEstimate(String key, String type, String location, SourceScan scan, int modelCalls,
                                 int githubCallsPlanned) {
    }

    public ImportRun {
        Ids.requireImportRunId(id);
        if (status == null) {
            throw new IllegalArgumentException("导入记录必须有状态");
        }
        sources = sources == null ? List.of() : List.copyOf(sources);
        extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }
}
