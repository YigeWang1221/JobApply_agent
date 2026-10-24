package jobapply.harness.policies;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 提交守卫的词表。写在代码里，只能由开发代理修改；运行时的 skill、站点记录、用户确认都不能删减它（C004 决定 6）。
 * 匹配前先 {@link #normalize(String)}：去掉首尾空格、全角转半角（NFKC）、忽略大小写、连续空白合成一个空格。
 * 中文按子串匹配，并且忽略空白（站点常把按钮写成「提 交」）；英文按词首匹配（{@code apply} 能命中 {@code Apply now}、{@code applying}，不命中词中间的片段）。
 * 「My Applications」不会命中 {@code apply}（拼写是 appli-）。
 */
public final class SubmitWords {

    /** 硬边界：按钮可能完成提交，由用户自己点。 */
    static final List<String> HARD_ZH = List.of(
            "提交", "投递", "申请", "确认", "确定", "递交", "完成", "发送", "预览并提交", "立即申请", "确认意向");
    static final List<String> HARD_EN = List.of(
            "submit", "apply", "send", "finish", "confirm", "complete", "finalize", "done");

    /** 软边界：「下一步 / 保存」一类，要看模型给的判断。往前走的（下一步）和保存的分开：页面上的硬信号只对往前走的算。 */
    static final List<String> NEXT_ZH = List.of("下一步", "下一页", "继续");
    static final List<String> NEXT_EN = List.of("next", "continue", "proceed");
    static final List<String> SAVE_ZH = List.of("保存", "暂存", "存草稿", "草稿");
    static final List<String> SAVE_EN = List.of("save", "draft");

    /** 疑似已提交的页面。 */
    static final List<String> SUBMITTED_ZH = List.of(
            "投递成功", "申请成功", "提交成功", "申请已提交", "简历已投递", "已成功投递", "已成功提交", "感谢您的申请",
            "感谢你的申请", "感谢投递", "感谢您的投递");
    static final List<String> SUBMITTED_EN = List.of(
            "application submitted", "application has been submitted", "application was submitted",
            "successfully submitted", "submission received", "application received",
            "we have received your application", "we've received your application", "thank you for applying",
            "thanks for applying", "thank you for your application", "thanks for your application");

    /** 安全验证。 */
    static final List<String> VERIFICATION_ZH = List.of(
            "验证码", "安全验证", "人机验证", "滑块", "拖动滑块", "请完成验证", "身份验证", "行为验证");
    static final List<String> VERIFICATION_EN = List.of(
            "captcha", "recaptcha", "hcaptcha", "verify you are human", "verify you're human", "i'm not a robot",
            "i am not a robot", "security check", "are you a robot");

    /** 申请 Agent 结束时页面已经像投递成功了，给用户的话以这句开头（界面据此提示「确认一下」，C007）。 */
    public static final String SUBMITTED_NOTICE = "页面显示已经投递了。";

    private SubmitWords() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('’', '\'')
                .toLowerCase(Locale.ROOT);
        return folded.replaceAll("\\s+", " ").trim();
    }

    public static boolean isHard(String name) {
        return matches(normalize(name), HARD_ZH, HARD_EN);
    }

    public static boolean isSoft(String name) {
        return isNext(name) || matches(normalize(name), SAVE_ZH, SAVE_EN);
    }

    /** 软边界里往前走的那种（下一步、继续、Next）：可能直接把申请交出去。 */
    public static boolean isNext(String name) {
        return matches(normalize(name), NEXT_ZH, NEXT_EN);
    }

    public static boolean looksSubmitted(String pageText) {
        return containsAny(normalize(pageText), SUBMITTED_ZH, SUBMITTED_EN);
    }

    public static boolean looksLikeVerification(String pageText) {
        return containsAny(normalize(pageText), VERIFICATION_ZH, VERIFICATION_EN);
    }

    /** 中文词表用：去掉全部空白。 */
    static String compact(String normalized) {
        return normalized.replace(" ", "");
    }

    private static boolean matches(String normalized, List<String> zh, List<String> en) {
        String compact = compact(normalized);
        if (zh.stream().anyMatch(compact::contains)) {
            return true;
        }
        return en.stream().anyMatch(word -> wordPrefix(word).matcher(normalized).find());
    }

    /** 页面级的短语：中英文都按子串匹配（短语本身已经足够具体）。 */
    private static boolean containsAny(String normalized, List<String> zh, List<String> en) {
        String compact = compact(normalized);
        return zh.stream().anyMatch(compact::contains) || en.stream().anyMatch(normalized::contains);
    }

    private static Pattern wordPrefix(String word) {
        // 词首：前面不是字母或数字；后面可以接 ing / s / ed 等词尾
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word));
    }
}
