package jobapply.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次导入整理出的一个候选（存在导入记录的 {@code candidates} 里，C004-A-2）：一条事实或一张项目卡，等用户确认。
 * 不放原文，只放候选的值和出处定位。
 *
 * @param id           {@code f-NNN}（事实）/ {@code p-NNN}（项目卡），在一次导入里唯一
 * @param kind         {@value #FACT} / {@value #PROJECT}
 * @param group        分组（{@link EvidenceTier} 的小写名字：basic、education、experience、projects、skills、outcomes、metrics）
 * @param key          事实的键；项目卡是 {@code projects.<slug>}
 * @param factClass    事实的分级 {@code A} / {@code B}；项目卡为 null
 * @param project      项目卡候选；事实为 null
 * @param evidence     支撑这条候选的一句论断（模型起草，可以为 null），也是台账里论断的文字
 * @param claim        整理时在台账里建的论断（状态 {@code needs_verification}）；基础信息和「相同」的候选没有
 * @param comparison   和已有事实层对比：{@value #NEW} / {@value #DIFFERENT} / {@value #SAME}
 * @param existing     「不同」时事实层里现在的值（{@code value} / {@code zh} / {@code en}，项目卡是标题）
 * @param alternatives 其它来源给出的不同写法
 * @param notes        给用户看的提示（例如出处无效、请核对）
 * @param decision     用户的决定；还没决定时为 null
 */
public record ImportCandidate(
        String id,
        String kind,
        String group,
        String key,
        String value,
        String zh,
        String en,
        String factClass,
        ProjectCard project,
        String evidence,
        List<Origin> origins,
        String claim,
        String comparison,
        Map<String, String> existing,
        List<Alternative> alternatives,
        List<String> notes,
        Decision decision) {

    public static final String FACT = "fact";
    public static final String PROJECT = "project";
    public static final String NEW = "new";
    public static final String DIFFERENT = "different";
    public static final String SAME = "same";

    /** 出处：来源键和定位（文件路径，可以带 {@code #L行号}）；定位可以为 null。 */
    public record Origin(String source, String locator) {
    }

    /** 另一个来源给出的不同写法。 */
    public record Alternative(String source, String value, String zh, String en) {
    }

    /**
     * @param decision {@code accept} / {@code edit} / {@code reject} / {@code conflict}；「相同」的候选自动记为 {@code same}
     * @param use      论断的用途（{@code forms_and_resume} / {@code interview_only} / {@code none}）
     * @param warnings 确认时的提示（例如译文没有起草成功）
     */
    public record Decision(String decision, String use, String at, List<String> warnings) {

        public Decision {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public ImportCandidate {
        if (!FACT.equals(kind) && !PROJECT.equals(kind)) {
            throw new IllegalArgumentException("候选的类型只能是 fact 或 project：" + kind);
        }
        origins = origins == null ? List.of() : List.copyOf(origins);
        existing = existing == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(existing));
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean forProject() {
        return PROJECT.equals(kind);
    }

    public ImportCandidate withId(String newId) {
        return new ImportCandidate(newId, kind, group, key, value, zh, en, factClass, project, evidence, origins, claim,
                comparison, existing, alternatives, notes, decision);
    }

    public ImportCandidate withClaim(String claimId) {
        return new ImportCandidate(id, kind, group, key, value, zh, en, factClass, project, evidence, origins, claimId,
                comparison, existing, alternatives, notes, decision);
    }

    public ImportCandidate withDecision(Decision newDecision) {
        return new ImportCandidate(id, kind, group, key, value, zh, en, factClass, project, evidence, origins, claim,
                comparison, existing, alternatives, notes, newDecision);
    }
}
