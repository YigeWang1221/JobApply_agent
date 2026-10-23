package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 目录选择框：只列子目录，不列文件；隐藏目录、生成目录、秘密目录和符号链接不列；storage 相关的目录不能登记。
 * 目录都在 @TempDir 里现造。
 */
class DirectoryBrowserTest {

    @TempDir
    Path temp;

    @Test
    void listsOnlyVisibleSubdirectories() throws Exception {
        Path home = temp.toRealPath();
        Files.createDirectories(home.resolve("projects/demo"));
        Files.createDirectories(home.resolve("notes"));
        Files.createDirectories(home.resolve(".hidden"));
        Files.createDirectories(home.resolve("node_modules"));
        Files.createDirectories(home.resolve("secrets"));
        Files.writeString(home.resolve("resume.md"), "虚构");
        Files.createSymbolicLink(home.resolve("link"), home.resolve("notes"));
        Files.createDirectories(home.resolve("storage/base"));

        DirectoryBrowser browser = new DirectoryBrowser(home.resolve("storage"));
        DirectoryBrowser.Listing listing = browser.list(home.toString());

        assertThat(listing.path()).isEqualTo(home.toString());
        assertThat(listing.parent()).isEqualTo(home.getParent().toString());
        assertThat(listing.registrable()).isFalse();
        assertThat(listing.directories()).extracting(DirectoryBrowser.Entry::name)
                .containsExactly("notes", "projects", "storage");
        assertThat(listing.directories()).filteredOn(e -> e.name().equals("storage")).singleElement()
                .extracting(DirectoryBrowser.Entry::registrable).isEqualTo(false);
        assertThat(listing.directories()).filteredOn(e -> e.name().equals("projects")).singleElement()
                .satisfies(e -> {
                    assertThat(e.registrable()).isTrue();
                    assertThat(e.path()).isEqualTo(home.resolve("projects").toString());
                });
        assertThat(browser.list("文件来自于「" + home.resolve("projects") + "」").directories())
                .extracting(DirectoryBrowser.Entry::name).containsExactly("demo");
        assertThat(browser.list(home.resolve("storage/base").toString()).registrable()).isFalse();
    }

    @Test
    void badPathsAreRefused() {
        DirectoryBrowser browser = new DirectoryBrowser(temp.resolve("storage"));
        assertThatThrownBy(() -> browser.list("relative/dir")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> browser.list(temp.resolve("missing").toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(browser.list(null).path()).isNotBlank();
    }
}
