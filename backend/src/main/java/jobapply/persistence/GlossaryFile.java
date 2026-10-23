package jobapply.persistence;

import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code base/glossary.md}：中英术语对照（学校、公司、学位、技术名词的固定译法），起草译文时按它来。
 * 一行一个术语，以中文为键；读的时候保留未识别的列和表格前后的内容。
 */
public final class GlossaryFile {

    static final String ZH = "中文";
    static final String EN = "English";
    static final String KIND = "类别";
    static final String NOTES = "说明";
    private static final List<String> KNOWN = List.of(ZH, EN, KIND, NOTES);
    private static final String HEADING = "# 术语对照\n\n";

    /**
     * @param kind  学校 / 公司 / 学位 / 技术 等，自由填写
     * @param extra 未识别的列
     */
    public record Term(String zh, String en, String kind, String notes, Map<String, String> extra) {

        public Term {
            if (zh == null || zh.isBlank() || en == null || en.isBlank()) {
                throw new IllegalArgumentException("术语必须有中文和英文");
            }
            kind = kind == null ? "" : kind;
            notes = notes == null ? "" : notes;
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }
    }

    private final StorageLayout layout;

    public GlossaryFile(StorageLayout layout, Clock clock) {
        this.layout = layout;
    }

    public synchronized List<Term> list() {
        return terms(table());
    }

    public synchronized Optional<Term> find(String zh) {
        return list().stream().filter(term -> term.zh().equals(zh)).findFirst();
    }

    /** 新增或替换（按中文）。其它行（包括不完整的行）原样保留。 */
    public synchronized Term put(Term term) {
        MarkdownTable.requireCell(ZH, term.zh());
        MarkdownTable table = table();
        Map<String, String> row = new LinkedHashMap<>(term.extra());
        row.put(ZH, term.zh());
        row.put(EN, term.en());
        row.put(KIND, term.kind());
        row.put(NOTES, term.notes());
        int index = -1;
        for (int i = 0; i < table.rows.size(); i++) {
            if (term.zh().equals(table.rows.get(i).get(ZH))) {
                index = i;
            }
        }
        if (index < 0) {
            table.rows.add(row);
        } else {
            table.rows.set(index, row);
        }
        AtomicFiles.write(layout.baseGlossary(), table.render());
        return term;
    }

    private MarkdownTable table() {
        return Files.exists(layout.baseGlossary())
                ? MarkdownTable.parse(AtomicFiles.read(layout.baseGlossary()), ZH, KNOWN, HEADING)
                : MarkdownTable.empty(HEADING, KNOWN);
    }

    private List<Term> terms(MarkdownTable table) {
        List<Term> terms = new ArrayList<>();
        for (Map<String, String> row : table.rows) {
            String zh = row.getOrDefault(ZH, "");
            String en = row.getOrDefault(EN, "");
            if (zh.isEmpty() || en.isEmpty()) {
                continue;
            }
            terms.add(new Term(zh, en, row.getOrDefault(KIND, ""), row.getOrDefault(NOTES, ""),
                    MarkdownTable.extra(row, KNOWN)));
        }
        return terms;
    }
}
