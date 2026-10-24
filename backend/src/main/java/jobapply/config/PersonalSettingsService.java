package jobapply.config;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.PersonalKey;
import jobapply.domain.Placeholder;
import jobapply.persistence.FactsFile;
import jobapply.persistence.PersonalEnv;
import jobapply.persistence.StorageLayout;

/**
 * 设置页「敏感信息」（C014-D）：预设的键（{@link PersonalKey#PRESETS}）和用户自己加的键，每个只告诉页面「已设置 / 未设置」，
 * 值从不返回。保存一个值时写进 {@code personal.env}，并在 base 资料里登记一条 C 级事实，值是占位符 {@code {KEY}}，
 * 用户不用自己改资料文件；清掉值时事实留着（守卫会说这个占位符没有值）。
 */
public final class PersonalSettingsService {

    /** 一行：键名、中英文名、用在哪些字段、是不是预设的、有没有值。没有值本身。 */
    public record Row(String key, String zh, String en, String fields, boolean preset, boolean set) {
    }

    public record View(List<Row> keys) {
    }

    // 和 FactWriter 写 base facts 用的标题一样
    private static final String FACTS_TITLE = "个人基础事实";

    private final StorageLayout layout;
    private final PersonalEnv env;
    private final Clock clock;

    public PersonalSettingsService(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.env = new PersonalEnv(layout);
        this.clock = clock;
    }

    public synchronized View view() {
        Set<String> named = env.names();
        Map<String, Row> rows = new LinkedHashMap<>();
        for (PersonalKey preset : PersonalKey.PRESETS) {
            rows.put(preset.key(), new Row(preset.key(), preset.zh(), preset.en(), preset.fields(), true,
                    named.contains(preset.key())));
        }
        // 自己加的键：资料里登记过的占位符，和 personal.env 里有的键
        for (Fact fact : FactsFile.read(layout.baseFacts())) {
            if (fact.unset() || fact.factClass() != FactClass.C) {
                continue;
            }
            Placeholder.keyOf(fact.value()).filter(key -> !rows.containsKey(key)).ifPresent(key ->
                    rows.put(key, new Row(key, fact.notes() == null ? key : fact.notes(), "", "", false,
                            named.contains(key))));
        }
        for (String key : named) {
            rows.putIfAbsent(key, new Row(key, key, "", "", false, true));
        }
        return new View(List.copyOf(rows.values()));
    }

    /**
     * 写一个值，并在 base 资料里登记占位符。
     *
     * @param label 自己加的键的名字（预设的键不用）；没有时用键名
     */
    public synchronized Row set(String key, String value, String label) {
        env.set(key, value);
        register(key, label);
        return row(key);
    }

    /** 清掉一个值；资料里的占位符留着。 */
    public synchronized Row clear(String key) {
        env.remove(key);
        return row(key);
    }

    private Row row(String key) {
        return view().keys().stream().filter(row -> row.key().equals(key)).findFirst()
                .orElse(new Row(key, key, "", "", false, env.has(key)));
    }

    /** base 资料里还没有这个占位符时加一条 C 级事实 {@code sensitive.<键名小写>: {KEY}}。 */
    private void register(String key, String label) {
        PersonalKey preset = PersonalKey.preset(key).orElse(null);
        String factKey = (preset != null ? preset : new PersonalKey(key, key, "", "")).factKey();
        List<Fact> facts = new ArrayList<>(FactsFile.read(layout.baseFacts()));
        boolean registered = facts.stream().anyMatch(fact -> !fact.unset() && fact.factClass() == FactClass.C
                && Placeholder.keyOf(fact.value()).filter(key::equals).isPresent());
        if (registered) {
            return;
        }
        String notes = preset != null ? preset.zh() + " / " + preset.en()
                : label == null || label.isBlank() ? null : label.strip().replace('\n', ' ');
        Fact fact = new Fact(factKey, Placeholder.of(key), null, null, FactClass.C, "user-confirmed", null, null,
                LocalDate.now(clock).toString(), null, null, null, notes, false);
        facts.removeIf(existing -> existing.key().equals(factKey));
        facts.add(fact);
        FactsFile.replace(layout.baseFacts(), FACTS_TITLE, facts);
    }
}
