package jobapply.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 事实键的前缀 → 内容分组，供整理和确认页面分组显示。<b>不是证据门槛</b>：用户确认的经历、技能、成果、指标
 * 没有关联论断也可以保存（memory.md「来源与使用：用户确认优先」，2026-10-22 用户修订）。
 * 唯一的检查是：填了的论断引用必须在台账里存在。
 */
public enum EvidenceTier {

    BASIC, EDUCATION, EXPERIENCE, PROJECTS, SKILLS, OUTCOMES, METRICS;

    /** 按键的第一段分组；不认识的前缀（联系方式、链接、语言能力等）归为基础信息。 */
    public static EvidenceTier of(String key) {
        String head = key == null ? "" : key.split("\\.", 2)[0];
        return switch (head) {
            case "education" -> EDUCATION;
            case "experience" -> EXPERIENCE;
            case "projects" -> PROJECTS;
            case "skills" -> SKILLS;
            case "outcomes" -> OUTCOMES;
            case "metrics" -> METRICS;
            default -> BASIC;
        };
    }

    /** 检查填了的论断引用都在台账里；没填的不检查。不通过时报出全部缺失的引用。 */
    public static void requireKnownClaims(Collection<Fact> facts, Set<String> claimIds) {
        List<String> missing = new ArrayList<>();
        for (Fact fact : facts) {
            if (fact.claim() != null && !claimIds.contains(fact.claim())) {
                missing.add(fact.key() + " → " + fact.claim());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("论断台账里没有这些引用：" + String.join("，", missing));
        }
    }
}
