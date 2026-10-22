package jobapply.domain;

/**
 * 一条事实条目（{@code facts.md} / {@code overrides.md} 中的一个 {@code ## key}）。
 * {@code unset} 为 true 时表示屏蔽继承来的同名条目，此时不需要值和分级。
 * C 级字段不允许构造，因而不可能被写进任何文件。
 */
public record Fact(
        String key,
        String value,
        FactClass factClass,
        String source,
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
        if (!unset && (value == null || factClass == null)) {
            throw new IllegalArgumentException("事实条目必须有 value 和 class：" + key);
        }
    }

    public static Fact of(String key, String value, FactClass factClass, String source, String confirmedAt) {
        return new Fact(key, value, factClass, source, confirmedAt, null, null, null, null, false);
    }

    public static Fact unsetOf(String key) {
        return new Fact(key, null, null, null, null, null, null, null, null, true);
    }
}
