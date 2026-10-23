package jobapply.tools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快照 YAML 里 ref → 角色、名字、祖先节点的索引，供守卫查目标元素。格式按 @playwright/mcp 0.0.83（playwright-core 的
 * {@code renderAriaSnapshotAsYaml}）：每行 {@code - role "名字" [属性]… [ref=e12]}，名字是 JSON 字符串；
 * 整个键需要转义时用单引号包起来；行尾可能带 {@code : 文字} 或 {@code :}（下面有子节点）。
 * 带 ref 但解析不出角色的行记为未知（{@link Node#known()} 为 false），不猜。
 */
public final class SnapshotIndex {

    /** ref 的格式：{@code e12}；iframe 里是 {@code f1e3}。 */
    public static final Pattern REF = Pattern.compile("(f\\d+)?e\\d+");

    private static final Pattern REF_ATTR = Pattern.compile("\\[ref=([^\\]\\s]+)]");
    private static final Pattern ROLE = Pattern.compile("[a-z][a-zA-Z]*");

    /**
     * 快照里的一个节点。
     *
     * @param role      角色；未知时为 null
     * @param name      可访问名字；没有名字时为空字符串
     * @param text      行尾的文字（输入框的当前值、链接的文字等）；没有时为空字符串
     * @param ancestors 从最外层到父节点
     * @param known     是否成功解析出角色
     */
    public record Node(String ref, String role, String name, String text, List<Ancestor> ancestors, boolean known) {
    }

    /** 祖先节点：只留角色和名字。 */
    public record Ancestor(String role, String name) {
    }

    private record Line(String role, String name, String text, String ref) {
    }

    private static final SnapshotIndex EMPTY = new SnapshotIndex(Map.of());

    private final Map<String, Node> nodes;

    private SnapshotIndex(Map<String, Node> nodes) {
        this.nodes = nodes;
    }

    public static SnapshotIndex empty() {
        return EMPTY;
    }

    public static SnapshotIndex of(PageSnapshot page) {
        return page == null ? EMPTY : parse(page.snapshot());
    }

    public static SnapshotIndex parse(String yaml) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        Deque<Map.Entry<Integer, Ancestor>> stack = new ArrayDeque<>();
        for (String raw : (yaml == null ? "" : yaml).replace("\r\n", "\n").split("\n")) {
            int indent = indentOf(raw);
            String body = raw.substring(indent);
            if (!body.startsWith("- ")) {
                continue;
            }
            while (!stack.isEmpty() && stack.peekLast().getKey() >= indent) {
                stack.removeLast();
            }
            List<Ancestor> ancestors = stack.stream().map(Map.Entry::getValue).toList();
            Optional<Line> line = parseLine(body.substring(2));
            if (line.isPresent()) {
                Line parsed = line.get();
                if (parsed.ref() != null) {
                    nodes.putIfAbsent(parsed.ref(), new Node(parsed.ref(), parsed.role(), parsed.name(), parsed.text(),
                            ancestors, true));
                }
                stack.addLast(Map.entry(indent, new Ancestor(parsed.role(), parsed.name())));
            } else {
                Matcher ref = REF_ATTR.matcher(body);
                if (ref.find()) {
                    nodes.putIfAbsent(ref.group(1), new Node(ref.group(1), null, "", "", ancestors, false));
                }
                stack.addLast(Map.entry(indent, new Ancestor(null, "")));
            }
        }
        return new SnapshotIndex(Map.copyOf(nodes));
    }

    public Optional<Node> find(String ref) {
        return Optional.ofNullable(ref == null ? null : nodes.get(ref));
    }

    public boolean contains(String ref) {
        return ref != null && nodes.containsKey(ref);
    }

    public int size() {
        return nodes.size();
    }

    /** 全部节点（按 ref 去重，顺序不保证）。 */
    public Collection<Node> nodes() {
        return nodes.values();
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** 解析 {@code - } 之后的部分。解析不了时返回空。 */
    private static Optional<Line> parseLine(String body) {
        String key;
        String rest;
        if (body.startsWith("'")) {
            int end = closingSingleQuote(body);
            if (end < 0) {
                return Optional.empty();
            }
            key = body.substring(1, end).replace("''", "'");
            rest = body.substring(end + 1);
        } else {
            int split = keyEnd(body);
            key = body.substring(0, split);
            rest = body.substring(split);
        }
        Matcher role = ROLE.matcher(key);
        if (!role.lookingAt()) {
            return Optional.empty();
        }
        String roleName = role.group();
        int pos = role.end();
        String name = "";
        if (pos < key.length() && key.charAt(pos) == ' ' && pos + 1 < key.length() && key.charAt(pos + 1) == '"') {
            int[] end = new int[1];
            String decoded = jsonString(key, pos + 1, end);
            if (decoded == null) {
                return Optional.empty();
            }
            name = decoded;
            pos = end[0];
        }
        String attrs = key.substring(pos);
        if (!attrs.isBlank() && !attrs.trim().startsWith("[")) {
            return Optional.empty();
        }
        Matcher ref = REF_ATTR.matcher(attrs);
        String refValue = ref.find() ? ref.group(1) : null;
        String text = "";
        if (rest.startsWith(":")) {
            text = unquoteValue(rest.substring(1).trim());
        } else if (!rest.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Line(roleName, name, text, refValue));
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

    /** 从 {@code start}（指向开头的双引号）解析一个 JSON 字符串；{@code end[0]} 设为结尾双引号之后的位置。 */
    private static String jsonString(String s, int start, int[] end) {
        StringBuilder out = new StringBuilder();
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                end[0] = i + 1;
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= s.length()) {
                return null;
            }
            char e = s.charAt(i);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 >= s.length()) {
                        return null;
                    }
                    try {
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                    i += 4;
                }
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    private static String unquoteValue(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            int[] end = new int[1];
            String decoded = jsonString(value, 0, end);
            return decoded == null ? value : decoded;
        }
        return value;
    }
}
