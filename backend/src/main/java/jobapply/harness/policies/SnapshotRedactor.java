package jobapply.harness.policies;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.tools.PageSnapshot;

/**
 * 遮掉快照里 C 级字段的值（C004-B「关键规则 · 隐私」）。用户接力时亲手填的 C 级字段，之后的快照里会带着值，
 * 例如 {@code - textbox "身份证号" [ref=e5]: …}。发给模型、写报告、交给打转检测比较之前都先过一遍；
 * 守卫仍然用原始快照判断（只在内存里）。
 * <p>
 * 规则：字段类的行（输入框、下拉框、勾选框、单选、选项等），自己的名字或任何一个祖先的名字命中
 * {@link FieldClassGuard#isClassC(String)} 时，这一行和它下面的所有行：行尾的值换成 {@link FieldClassGuard#REMOVED}，
 * 去掉 {@code [checked]}、{@code [selected]}、{@code [pressed]} 这类状态。其它行原样保留，ref 不变。
 * 行的写法按 @playwright/mcp 0.0.83（见 {@link jobapply.tools.SnapshotIndex}）。
 */
public final class SnapshotRedactor {

    static final Set<String> FIELD_ROLES = Set.of(
            "textbox", "searchbox", "combobox", "spinbutton", "slider", "listbox", "option", "checkbox", "radio",
            "radiogroup", "switch", "menuitemcheckbox", "menuitemradio");

    private static final Pattern ROLE_AND_NAME = Pattern.compile("([a-z][a-zA-Z]*)(?: \"((?:[^\"\\\\]|\\\\.)*)\")?");
    private static final Pattern STATE = Pattern.compile(
            " ?\\[(?:checked|selected|pressed)(?:=[^\\]]*)?]");

    private SnapshotRedactor() {
    }

    public static PageSnapshot redact(PageSnapshot page) {
        if (page == null) {
            return null;
        }
        return new PageSnapshot(page.url(), page.title(), redact(page.snapshot()), page.modalState());
    }

    public static String redact(String yaml) {
        if (yaml == null || yaml.isEmpty()) {
            return yaml == null ? "" : yaml;
        }
        record Open(int indent, String name, boolean classC) {
        }
        Deque<Open> stack = new ArrayDeque<>();
        List<String> out = new ArrayList<>();
        for (String raw : yaml.replace("\r\n", "\n").split("\n", -1)) {
            int indent = indentOf(raw);
            String body = raw.substring(indent);
            if (!body.startsWith("- ")) {
                boolean inside = !stack.isEmpty() && stack.peekLast().classC() && indent > stack.peekLast().indent();
                out.add(inside && !body.isBlank() ? raw.substring(0, indent) + FieldClassGuard.REMOVED : raw);
                continue;
            }
            while (!stack.isEmpty() && stack.peekLast().indent() >= indent) {
                stack.removeLast();
            }
            boolean parentClassC = !stack.isEmpty() && stack.peekLast().classC();
            String item = body.substring(2);
            boolean quoted = item.startsWith("'");
            int keyEnd = quoted ? closingSingleQuote(item) + 1 : keyEnd(item);
            if (quoted && keyEnd == 0) {
                keyEnd = item.length();
            }
            String key = quoted ? item.substring(1, Math.max(1, keyEnd - 1)) : item.substring(0, keyEnd);
            String rest = item.substring(keyEnd);
            Matcher matcher = ROLE_AND_NAME.matcher(key);
            String role = matcher.lookingAt() ? matcher.group(1) : null;
            String name = role != null && matcher.group(2) != null ? matcher.group(2) : "";
            boolean classC = parentClassC || role != null && FIELD_ROLES.contains(role)
                    && (FieldClassGuard.isClassC(name) || stack.stream().anyMatch(open -> FieldClassGuard.isClassC(open.name())));
            stack.addLast(new Open(indent, name, classC));
            if (!classC) {
                out.add(raw);
                continue;
            }
            String cleanKey = STATE.matcher(item.substring(0, keyEnd)).replaceAll("");
            String value = rest.startsWith(":") ? rest.substring(1) : rest;
            String cleanRest = rest.startsWith(":") && !value.isBlank() ? ": " + FieldClassGuard.REMOVED : rest;
            out.add(raw.substring(0, indent) + "- " + cleanKey + cleanRest);
        }
        return String.join("\n", out);
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** 不带单引号的键到哪里结束：名字（JSON 字符串）外面的第一个 {@code :}。 */
    private static int keyEnd(String body) {
        boolean inString = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == ':') {
                return i;
            }
        }
        return body.length();
    }

    private static int closingSingleQuote(String body) {
        for (int i = 1; i < body.length(); i++) {
            if (body.charAt(i) == '\'') {
                if (i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    return i;
                }
            }
        }
        return -1;
    }
}
