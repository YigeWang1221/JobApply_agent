package jobapply.domain;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 事实层的两种语言。文字字段成对保存（{@code zh} / {@code en}），日期和数字只存一份，取值时按语言格式输出
 * （memory.md「中英双语」）。
 */
public enum Language {

    ZH("zh", Locale.SIMPLIFIED_CHINESE, "y年M月", "y年M月d日"),
    EN("en", Locale.US, "MMM yyyy", "MMM d, yyyy");

    private static final Pattern YEAR_MONTH = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private final String code;
    private final Locale locale;
    private final DateTimeFormatter yearMonth;
    private final DateTimeFormatter date;

    Language(String code, Locale locale, String yearMonth, String date) {
        this.code = code;
        this.locale = locale;
        this.yearMonth = DateTimeFormatter.ofPattern(yearMonth, locale);
        this.date = DateTimeFormatter.ofPattern(date, locale);
    }

    /** 文件里的写法：{@code zh} / {@code en}。 */
    public String code() {
        return code;
    }

    public Language other() {
        return this == ZH ? EN : ZH;
    }

    public static Language of(String code) {
        for (Language language : values()) {
            if (language.code.equalsIgnoreCase(code == null ? "" : code.strip())) {
                return language;
            }
        }
        throw new IllegalArgumentException("不认识的语言（只有 zh、en）：" + code);
    }

    /**
     * 与语言无关的值按语言输出：{@code YYYY-MM}、{@code YYYY-MM-DD} 改成本语言的日期写法，其它原样返回。
     * 纯数字不在这里格式化（电话尾号、邮编也是数字），带单位的数值用 {@link #formatNumber(String)}。
     */
    public String formatValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            if (YEAR_MONTH.matcher(value).matches()) {
                return YearMonth.parse(value).format(yearMonth);
            }
            if (DATE.matcher(value).matches()) {
                return LocalDate.parse(value).format(date);
            }
        } catch (RuntimeException e) {
            return value;
        }
        return value;
    }

    /** 数值按语言分组（{@code 12000} → {@code 12,000}）；不是数值时原样返回。 */
    public String formatNumber(String value) {
        if (value == null || !NUMBER.matcher(value.strip()).matches()) {
            return value;
        }
        BigDecimal number = new BigDecimal(value.strip());
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMaximumFractionDigits(Math.max(0, number.scale()));
        format.setMinimumFractionDigits(Math.max(0, number.scale()));
        return format.format(number);
    }
}
