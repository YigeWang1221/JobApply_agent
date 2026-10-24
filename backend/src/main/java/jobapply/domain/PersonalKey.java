package jobapply.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 设置页「敏感信息」的一项（C014-D）：personal.env 里的键名、中英文名和它对应的表单字段。预设的一份在 {@link #PRESETS}，
 * 覆盖 C 级词表里能存成一个值的字段；法律声明和承诺勾选不是一个值，仍由用户亲手勾。用户也可以加自己的键。
 *
 * @param key    personal.env 里的键名，资料里写成 {@code {KEY}}
 * @param zh     中文名
 * @param en     英文名
 * @param fields 用在哪些表单字段上（给用户和模型看的说明）
 */
public record PersonalKey(String key, String zh, String en, String fields) {

    public PersonalKey {
        if (!Placeholder.isValidKey(key)) {
            throw new IllegalArgumentException("键名不合法：只允许大写字母、数字和下划线，不以数字开头");
        }
    }

    public static final List<PersonalKey> PRESETS = List.of(
            new PersonalKey("ID_TYPE", "证件类型", "ID type", "证件类型 / ID type"),
            new PersonalKey("ID_NUMBER", "身份证号", "National ID number", "身份证号、证件号码 / ID number, national ID"),
            new PersonalKey("PASSPORT_NUMBER", "护照号", "Passport number", "护照号码 / Passport number"),
            new PersonalKey("PASSPORT_EXPIRY", "护照有效期", "Passport expiry date", "护照有效期 / Passport expiry"),
            new PersonalKey("ETHNICITY", "民族", "Ethnicity", "民族 / Ethnicity, race"),
            new PersonalKey("RELIGION", "宗教信仰", "Religion", "宗教信仰 / Religion"),
            new PersonalKey("WORK_AUTHORIZATION", "工作授权", "Work authorization",
                    "工作授权、工作许可 / Authorized to work, right to work, work permit"),
            new PersonalKey("VISA_STATUS", "签证状态", "Visa status", "签证类型、居留 / Visa status"),
            new PersonalKey("NEEDS_SPONSORSHIP", "是否需要签证担保", "Requires visa sponsorship",
                    "是否需要担保 / Sponsorship"),
            new PersonalKey("EXPECTED_SALARY", "期望薪资", "Expected salary",
                    "期望薪资、期望月薪或年薪 / Expected salary, compensation"),
            new PersonalKey("CURRENT_SALARY", "当前薪资", "Current salary", "当前薪资 / Current salary"),
            new PersonalKey("HEALTH_STATUS", "健康状况", "Health status", "健康状况、病史 / Health, medical"),
            new PersonalKey("DISABILITY_STATUS", "残障状态", "Disability status", "残障 / Disability"),
            new PersonalKey("VETERAN_STATUS", "退伍军人身份", "Veteran status", "退伍军人 / Veteran"),
            new PersonalKey("CRIMINAL_RECORD", "犯罪记录", "Criminal record", "犯罪记录 / Criminal record, conviction"));

    public static Optional<PersonalKey> preset(String key) {
        return PRESETS.stream().filter(preset -> preset.key().equals(key)).findFirst();
    }

    /** 资料里登记这个占位符的事实键：{@code sensitive.<键名小写>}。 */
    public String factKey() {
        return FACT_PREFIX + key.toLowerCase(Locale.ROOT);
    }

    public static final String FACT_PREFIX = "sensitive.";
}
