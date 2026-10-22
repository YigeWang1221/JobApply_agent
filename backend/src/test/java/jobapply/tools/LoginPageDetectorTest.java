package jobapply.tools;

import static org.assertj.core.api.Assertions.assertThat;

import jobapply.tools.LoginPageDetector.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 页面数据全部虚构。
 */
class LoginPageDetectorTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            中文密码框             | https://jobs.example.com/apply/1      | 申请岗位  | - textbox "密码" [ref=e1]                      | LOGIN
            英文密码框             | https://jobs.example.com/apply/1      | Apply     | - textbox "Password" [ref=e1]                  | LOGIN
            passcode             | https://jobs.example.com/apply/1      | Apply     | - textbox "Enter passcode" [ref=e1]            | LOGIN
            SSO 地址加登录按钮      | https://sso.example.com/start         | 统一认证  | - button "登录" [ref=e1]                       | LOGIN
            login 路径加 Sign in  | https://jobs.example.com/login        | Welcome   | - link "Sign in with email" [ref=e1]           | LOGIN
            oauth 加 Continue    | https://id.example.com/oauth/authorize | Account  | - button "Continue with Google" [ref=e1]       | LOGIN
            验证码                | https://jobs.example.com/apply/1      | 申请岗位  | - text: 请输入验证码                             | CHALLENGE
            安全验证优先于密码框     | https://jobs.example.com/login        | 登录      | - text: 请完成安全验证\\n- textbox "密码"         | CHALLENGE
            captcha 在标题        | https://jobs.example.com/apply/1      | Captcha check | - generic [ref=e1]                         | CHALLENGE
            滑块                 | https://jobs.example.com/apply/1      | 申请      | - text: 拖动滑块完成拼图                          | CHALLENGE
            URL 有 login 但没按钮  | https://jobs.example.com/login/help   | 帮助      | - heading "常见问题" [level=1]                   | NONE
            有登录按钮但 URL 普通   | https://jobs.example.com/apply/1      | 申请      | - button "登录" [ref=e1]                       | NONE
            已登录的岗位页          | https://jobs.example.com/apply/1      | Backend   | - link "我的投递"\\n- button "立即申请"           | NONE
            author 不算 auth      | https://jobs.example.com/author/x     | 作者      | - button "Log in" [ref=e1]                     | NONE
            casual 不算 cas       | https://jobs.example.com/casual/list  | 岗位      | - button "登录" [ref=e1]                       | NONE
            """)
    void classifiesPages(String name, String url, String title, String snapshot, Kind expected) {
        PageSnapshot page = new PageSnapshot(url, title, snapshot.replace("\\n", "\n"), "");

        LoginPageDetector.Detection detection = LoginPageDetector.detect(page);

        assertThat(detection.kind()).as(name).isEqualTo(expected);
        assertThat(detection.rule()).isNotBlank();
        assertThat(detection.blocksUser()).isEqualTo(expected != Kind.NONE);
    }
}
