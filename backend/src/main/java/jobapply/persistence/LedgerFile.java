package jobapply.persistence;

import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import jobapply.domain.Claim;
import jobapply.domain.Ids;

/**
 * {@code base/ledger.md}：论断台账。id 是 {@code c-NNNN}，全局连续编号；删除以后不复用
 * （用 frontmatter 的 {@code last_claim} 记住用过的最大编号）。读的时候保留未识别的列和表格前后的内容。
 * 格式见 plan/coding/02-core-agent/C004-A-1-profile-import.md「论断台账」。
 */
public final class LedgerFile {

    static final String ID = "id";
    static final String CATEGORY = "类别";
    static final String TEXT = "论断";
    static final String SOURCE = "来源";
    static final String LOCATOR = "出处";
    static final String VALUE = "值与条件";
    static final String STATUS = "状态";
    static final String USE = "用途";
    static final String CONFIRMED_AT = "确认时间";
    private static final List<String> KNOWN = List.of(ID, CATEGORY, TEXT, SOURCE, LOCATOR, VALUE, STATUS, USE,
            CONFIRMED_AT);
    private static final String HEADING = "\n# 论断台账\n\n";
    private static final String LAST = "last_claim";

    private final StorageLayout layout;

    public LedgerFile(StorageLayout layout, Clock clock) {
        this.layout = layout;
    }

    public synchronized List<Claim> list() {
        return List.copyOf(load().claims);
    }

    public synchronized Set<String> ids() {
        Set<String> ids = new LinkedHashSet<>();
        load().claims.forEach(claim -> ids.add(claim.id()));
        return ids;
    }

    public synchronized Optional<Claim> find(String id) {
        return load().claims.stream().filter(claim -> claim.id().equals(id)).findFirst();
    }

    /** 给没有编号的论断编号并追加。 */
    public synchronized Claim add(Claim draft) {
        if (draft.id() != null) {
            throw new IllegalArgumentException("新论断不能自带 id：" + draft.id());
        }
        Ledger ledger = load();
        int next = ledger.last + 1;
        Claim claim = draft.withId(String.format("c-%04d", next));
        ledger.claims.add(claim);
        ledger.last = next;
        save(ledger, "base：台账新增 " + claim.id());
        return claim;
    }

    /** 一次给多条论断编号并追加（只写一次文件），按顺序返回编好号的论断。 */
    public synchronized List<Claim> addAll(List<Claim> drafts) {
        if (drafts.isEmpty()) {
            return List.of();
        }
        Ledger ledger = load();
        List<Claim> added = new ArrayList<>();
        for (Claim draft : drafts) {
            if (draft.id() != null) {
                throw new IllegalArgumentException("新论断不能自带 id：" + draft.id());
            }
            ledger.last++;
            Claim claim = draft.withId(String.format("c-%04d", ledger.last));
            ledger.claims.add(claim);
            added.add(claim);
        }
        save(ledger, "base：台账新增 " + added.getFirst().id() + " – " + added.getLast().id());
        return added;
    }

    /** 替换同一个 id 的论断（改状态、用途、出处等）。 */
    public synchronized Claim update(Claim claim) {
        Ledger ledger = load();
        int index = indexOf(ledger, claim.id());
        ledger.claims.set(index, claim);
        save(ledger, "base：台账修改 " + claim.id());
        return claim;
    }

    /** 删除一条；编号不会再分配给新论断。 */
    public synchronized void remove(String id) {
        Ledger ledger = load();
        ledger.claims.remove(indexOf(ledger, id));
        save(ledger, "base：台账删除 " + id);
    }

    private static int indexOf(Ledger ledger, String id) {
        for (int i = 0; i < ledger.claims.size(); i++) {
            if (ledger.claims.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new NoSuchElementException("台账里没有这条论断：" + id);
    }

    private static final class Ledger {
        Map<String, Object> frontmatter;
        MarkdownTable table;
        List<Claim> claims;
        int last;
    }

    private Ledger load() {
        Ledger ledger = new Ledger();
        String source = layout.baseLedger().toString();
        if (Files.exists(layout.baseLedger())) {
            FrontmatterFile.Document document = FrontmatterFile.parse(AtomicFiles.read(layout.baseLedger()), source);
            ledger.frontmatter = document.frontmatter();
            ledger.table = MarkdownTable.parse(document.body(), ID, KNOWN, HEADING);
        } else {
            ledger.frontmatter = new LinkedHashMap<>();
            ledger.table = MarkdownTable.empty(HEADING, KNOWN);
        }
        ledger.claims = new ArrayList<>();
        int last = number(String.valueOf(ledger.frontmatter.getOrDefault(LAST, "")));
        for (Map<String, String> row : ledger.table.rows) {
            String id = row.getOrDefault(ID, "");
            if (!Ids.isClaimId(id)) {
                continue;
            }
            try {
                String use = row.getOrDefault(USE, "");
                ledger.claims.add(new Claim(id, row.getOrDefault(CATEGORY, ""), row.getOrDefault(TEXT, ""),
                        row.getOrDefault(SOURCE, ""), row.getOrDefault(LOCATOR, ""), row.getOrDefault(VALUE, ""),
                        Claim.Status.of(row.getOrDefault(STATUS, "")), use.isEmpty() ? null : Claim.Use.of(use),
                        row.getOrDefault(CONFIRMED_AT, ""), MarkdownTable.extra(row, KNOWN)));
            } catch (IllegalArgumentException e) {
                throw new StorageFormatException(source, id + "：" + e.getMessage(), e);
            }
            last = Math.max(last, number(id));
        }
        ledger.last = last;
        return ledger;
    }

    private void save(Ledger ledger, String message) {
        ledger.table.rows.clear();
        for (Claim claim : ledger.claims) {
            Map<String, String> row = new LinkedHashMap<>(claim.extra());
            row.put(ID, claim.id());
            row.put(CATEGORY, claim.category());
            row.put(TEXT, claim.text());
            row.put(SOURCE, claim.source());
            row.put(LOCATOR, claim.locator());
            row.put(VALUE, claim.valueAndCondition());
            row.put(STATUS, claim.status().code());
            row.put(USE, claim.use().code());
            row.put(CONFIRMED_AT, claim.confirmedAt());
            ledger.table.rows.add(row);
        }
        Map<String, Object> frontmatter = new LinkedHashMap<>(ledger.frontmatter);
        frontmatter.put(LAST, String.format("c-%04d", ledger.last));
        AtomicFiles.write(layout.baseLedger(), FrontmatterFile.render(
                new FrontmatterFile.Document(frontmatter, ledger.table.render())));
    }

    private static int number(String id) {
        return Ids.isClaimId(id) ? Integer.parseInt(id.substring(2)) : 0;
    }
}
