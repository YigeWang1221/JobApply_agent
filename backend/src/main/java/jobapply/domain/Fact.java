package jobapply.domain;

import java.util.Set;

/**
 * 一条事实条目（{@code facts.md} / {@code overrides.md} 中的一个 {@code ## key}）。
 * {@code unset} 为 true 时表示屏蔽继承来的同名条目，此时不需要值和分级。
 * C 级字段不允许构造，因而不可能被写进任何文件。
 * <p>
 * 值有两种写法，二选一：与语言无关的 {@code value}（日期、数字、链接等），或者成对的文字 {@code zh} / {@code en}
 * （可以只有一种，另一种以后起草）。{@code reviewed} 是已经审阅过的语言；模型起草、还没审阅的不在里面。
 * {@code claim} 可选，关联论断台账（memory.md「资料导入与画像」）。
 */
public record Fact(
        String key,
        String value,
        String zh,
        String en,
        FactClass factClass,
        String source,
        String claim,
        Set<Language> reviewed,
        String confirmedAt,
        String validFrom,
        String validTo,
        String precision,
        String notes,
        boolean unset) {

    public Fact {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("事实条目必须有 key");
        }
        if (factClass == FactClass.C) {
            throw new IllegalArgumentException("C 级字段不允许存储：" + key);
        }
        if (value != null && (zh != null || en != null)) {
            throw new IllegalArgumentException("value 和 zh / en 只能二选一：" + key);
        }
        if (!unset && ((value == null && zh == null && en == null) || factClass == null)) {
            throw new IllegalArgumentException("事实条目必须有 value（或 zh / en）和 class：" + key);
        }
        if (claim != null && !Ids.isClaimId(claim)) {
            throw new IllegalArgumentException("论断 id 不合法（应为 c-NNNN）：" + key);
        }
        reviewed = reviewed == null ? Set.of() : Set.copyOf(reviewed);
    }

    public static Fact of(String key, String value, FactClass factClass, String source, String confirmedAt) {
        return new Fact(key, value, null, null, factClass, source, null, null, confirmedAt, null, null, null, null,
                false);
    }

    /** 文字字段：中英文成对，{@code reviewed} 是用户审阅过的语言。 */
    public static Fact bilingual(String key, String zh, String en, FactClass factClass, String source,
                                 String confirmedAt, Set<Language> reviewed) {
        return new Fact(key, null, zh, en, factClass, source, null, reviewed, confirmedAt, null, null, null, null,
                false);
    }

    public static Fact unsetOf(String key) {
        return new Fact(key, null, null, null, null, null, null, null, null, null, null, null, null, true);
    }

    public boolean isBilingual() {
        return value == null && !unset;
    }

    /** 某种语言的文字；没有时为 null。与语言无关的条目返回 null。 */
    public String text(Language language) {
        return language == Language.ZH ? zh : en;
    }

    public Fact withClaim(String claimId) {
        return new Fact(key, value, zh, en, factClass, source, claimId, reviewed, confirmedAt, validFrom, validTo,
                precision, notes, unset);
    }
}
