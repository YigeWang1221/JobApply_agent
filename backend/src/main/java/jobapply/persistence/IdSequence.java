package jobapply.persistence;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按日期连续编号的 id：{@code <prefix>NNN}，在已有名字里取最大序号加一。
 */
final class IdSequence {

    private IdSequence() {
    }

    static String next(String prefix, Collection<String> existing) {
        Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "(\\d{3,})");
        int max = 0;
        for (String name : existing) {
            Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return prefix + String.format("%03d", max + 1);
    }
}
