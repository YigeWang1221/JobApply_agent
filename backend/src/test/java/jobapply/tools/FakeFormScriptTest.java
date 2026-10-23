package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.SubmitWords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 表单夹具（{@code fixtures/forms/*.scenario.json}）本身是否写对，以及假服务端表单模式的细节。
 */
class FakeFormScriptTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String JOB = "2026-10-23-001";
    private static final Pattern REF = Pattern.compile("\\[ref=([^\\]\\s]+)]");

    @TempDir
    Path dir;

    @Test
    @SuppressWarnings("unchecked")
    void everyFormFixtureIsConsistent() throws Exception {
        List<Path> fixtures = fixtures();
        assertThat(fixtures).extracting(path -> path.getFileName().toString())
                .contains("multi-page.scenario.json", "submit-labels.scenario.json", "file-slots.scenario.json");
        for (Path fixture : fixtures) {
            String name = fixture.getFileName().toString().replace(".scenario.json", "");
            assertThat(fixture.resolveSibling(name + ".html")).as("每个夹具都有同名的 .html").exists();
            Map<String, Object> form = JSON.readValue(Files.readString(fixture), new TypeReference<>() {
            });
            assertThat(form.get("name")).as(name).isEqualTo(name);
            Map<String, Object> pages = (Map<String, Object>) form.get("pages");
            assertThat(pages).as(name).isNotEmpty();
            if (form.get("entry") != null) {
                assertThat(pages).as(name).containsKey((String) form.get("entry"));
            }
            for (Map.Entry<String, Object> entry : pages.entrySet()) {
                Map<String, Object> page = (Map<String, Object>) entry.getValue();
                String where = name + " / " + entry.getKey();
                assertThat((String) page.get("url")).as(where).startsWith("https://");
                String snapshot = page.get("snapshot") instanceof List<?> lines
                        ? String.join("\n", (List<String>) lines) : (String) page.get("snapshot");
                SnapshotIndex index = SnapshotIndex.parse(snapshot);
                List<String> refs = refs(snapshot);
                assertThat(refs).as(where + " 的 ref 不重复").doesNotHaveDuplicates();
                for (String ref : refs) {
                    assertThat(index.find(ref)).as(where + " " + ref).hasValueSatisfying(
                            node -> assertThat(node.known()).isTrue());
                }
                Map<String, Object> clicks = (Map<String, Object>) page.getOrDefault("clicks", Map.of());
                for (Map.Entry<String, Object> click : clicks.entrySet()) {
                    assertThat(refs).as(where + " 点击的 ref").contains(click.getKey());
                    Object action = click.getValue();
                    String to = action instanceof String text ? text : (String) ((Map<String, Object>) action).get("to");
                    if (to != null) {
                        assertThat(pages).as(where + " 点击后去的页面").containsKey(to);
                    }
                    if (action instanceof Map<?, ?> details && details.get("fileChooser") != null) {
                        assertThat(refs).as(where + " 文件名显示的位置").contains((String) details.get("fileChooser"));
                    }
                }
            }
        }
    }

    @Test
    void submitLabelsFixtureHasEveryHardButtonFromThePlan() throws Exception {
        String snapshot = snapshot("submit-labels", "buttons");
        for (String label : List.of("立即申请", "确认意向并投递", "预览并提交", "保存并提交", "Submit", "Apply", "Send",
                "Finish", "Confirm")) {
            assertThat(snapshot).contains("- button \"" + label + "\"");
            assertThat(SubmitWords.isHard(label)).as(label).isTrue();
        }
        assertThat(SubmitWords.isSoft("下一步")).isTrue();
    }

    @Test
    void userEditsShowUpInTheNextSnapshotOnce() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page");
        String url = fake.formUrl();

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            PageSnapshot first = session.open(url, JOB);
            assertThat(first.snapshot()).contains("- textbox \"姓名\" [ref=e3]\n");

            fake.userEdit("basic", "e3", "用户亲填");
            assertThat(session.snapshot(JOB).snapshot()).contains("[ref=e3]: 用户亲填");

            PageSnapshot overwritten = session.act(JOB, BrowserToolPolicy.TYPE,
                    Map.of("target", "e3", "text", "模型填的"),
                    BrowserToolPolicy.Context.model(SnapshotIndex.of(first), List.of(url), null));
            assertThat(overwritten.snapshot()).as("同一条 userEdit 不再应用").contains("[ref=e3]: 模型填的");
        }
    }

    @Test
    void unknownRefsAreToolErrorsAndCheckboxesRenderAsChecked() {
        FakeBrowser fake = FakeBrowser.create(dir).form("multi-page");
        String url = fake.formUrl();

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            PageSnapshot first = session.open(url, JOB);
            // e19 在第二页；用一份「旧」索引让白名单放行，看服务端怎么回
            BrowserToolPolicy.Context stale = BrowserToolPolicy.Context.model(
                    SnapshotIndex.parse("- button \"下一步\" [ref=e19]"), List.of(url), null);
            assertThatThrownBy(() -> session.act(JOB, BrowserToolPolicy.CLICK, Map.of("target", "e19"), stale))
                    .isInstanceOf(BrowserException.ToolFailed.class).hasMessageContaining("not found");
            assertThat(session.snapshot(JOB)).isEqualTo(first);
        }

        Matcher ref = REF.matcher("- checkbox \"同意隐私政策\" [ref=e15]");
        assertThat(ref.find()).isTrue();
        assertThat(FakeFormScript.withValue("- checkbox \"同意隐私政策\" [ref=e15]", ref, "true"))
                .isEqualTo("- checkbox \"同意隐私政策\" [checked] [ref=e15]");
        Matcher checked = REF.matcher("  - checkbox \"同意隐私政策\" [checked] [ref=e15]");
        assertThat(checked.find()).isTrue();
        assertThat(FakeFormScript.withValue("  - checkbox \"同意隐私政策\" [checked] [ref=e15]", checked, "false"))
                .isEqualTo("  - checkbox \"同意隐私政策\" [ref=e15]");
        Matcher text = REF.matcher("- generic \"简历文件\" [ref=e4]: 未上传");
        assertThat(text.find()).isTrue();
        assertThat(FakeFormScript.withValue("- generic \"简历文件\" [ref=e4]: 未上传", text, "a: b"))
                .isEqualTo("- generic \"简历文件\" [ref=e4]: \"a: b\"");
    }

    @Test
    void submittedPagesAreReachableOnlyThroughHardButtons() {
        FakeBrowser fake = FakeBrowser.create(dir).form("submit-labels");
        String url = fake.formUrl();

        try (BrowserSession session = new BrowserSession(fake.launch())) {
            PageSnapshot first = session.open(url, JOB);
            PageSnapshot next = session.act(JOB, BrowserToolPolicy.CLICK, Map.of("target", "e12"),
                    BrowserToolPolicy.Context.model(SnapshotIndex.of(first), List.of(url), null));

            assertThat(next.title()).endsWith("补充信息");
            assertThat(next.url()).isEqualTo(url);
        }
        assertThat(fake.calls(BrowserToolPolicy.CLICK)).extracting(args -> args.get("target")).containsExactly("e12");
    }

    @SuppressWarnings("unchecked")
    private static String snapshot(String fixture, String page) throws Exception {
        Map<String, Object> form = JSON.readValue(Files.readString(formsDir().resolve(fixture + ".scenario.json")),
                new TypeReference<>() {
                });
        Map<String, Object> pages = (Map<String, Object>) form.get("pages");
        return String.join("\n", (List<String>) ((Map<String, Object>) pages.get(page)).get("snapshot"));
    }

    private static List<String> refs(String snapshot) {
        List<String> refs = new ArrayList<>();
        Matcher matcher = REF.matcher(snapshot);
        while (matcher.find()) {
            refs.add(matcher.group(1));
        }
        return refs;
    }

    private static List<Path> fixtures() throws Exception {
        try (Stream<Path> files = Files.list(formsDir())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".scenario.json")).sorted().toList();
        }
    }

    private static Path formsDir() throws URISyntaxException {
        return Path.of(FakeFormScriptTest.class.getResource("/fixtures/forms").toURI());
    }
}
