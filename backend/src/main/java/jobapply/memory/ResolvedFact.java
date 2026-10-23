package jobapply.memory;

import jobapply.domain.FactClass;
import jobapply.domain.Language;

/**
 * 继承解析后的一个字段：值、分级和来源层。{@link #label()} 用于填写报告。
 *
 * @param value      文字字段是所取语言的文字，与语言无关的字段是原值
 * @param display    按语言输出的写法（日期、带单位的数值按语言格式化）；文字字段与 {@code value} 相同
 * @param language   文字取自哪种语言；与语言无关的字段为 null。要的语言没有文字时，取另一种语言并标为未审阅
 * @param unreviewed 取到的文字还没有审阅过（模型起草的译文，或者用另一种语言代替）
 * @param claim      取到的那一条事实关联的论断编号（{@code c-NNNN}）；没有关联时为 null。填表时按论断的用途和状态筛选
 */
public record ResolvedFact(
        String key,
        String value,
        String display,
        FactClass factClass,
        Layer layer,
        String layerId,
        String confirmedAt,
        Language language,
        boolean unreviewed,
        String claim) {

    /** 没有关联论断的字段。 */
    public ResolvedFact(String key, String value, String display, FactClass factClass, Layer layer, String layerId,
                        String confirmedAt, Language language, boolean unreviewed) {
        this(key, value, display, factClass, layer, layerId, confirmedAt, language, unreviewed, null);
    }

    public enum Layer {
        SESSION, PROJECT, BASE
    }

    public String label() {
        String from = switch (layer) {
            case SESSION -> "来自本 Session 答案";
            case PROJECT -> "来自 Project " + layerId + " / overrides"
                    + (confirmedAt == null ? "" : "，" + confirmedAt + " 确认");
            case BASE -> "来自 base（pin " + layerId + "）";
        };
        return unreviewed ? from + "；" + language.code() + " 文字未审阅" : from;
    }
}
