package jobapply.memory;

import jobapply.domain.FactClass;

/**
 * 继承解析后的一个字段：值、分级和来源层。{@link #label()} 用于填写报告。
 */
public record ResolvedFact(
        String key,
        String value,
        FactClass factClass,
        Layer layer,
        String layerId,
        String confirmedAt) {

    public enum Layer {
        SESSION, PROJECT, BASE
    }

    public String label() {
        return switch (layer) {
            case SESSION -> "来自本 Session 答案";
            case PROJECT -> "来自 Project " + layerId + " / overrides"
                    + (confirmedAt == null ? "" : "，" + confirmedAt + " 确认");
            case BASE -> "来自 base（pin " + layerId + "）";
        };
    }
}
