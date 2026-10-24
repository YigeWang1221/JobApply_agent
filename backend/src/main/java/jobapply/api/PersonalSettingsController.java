package jobapply.api;

import jobapply.config.PersonalSettingsService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设置页「敏感信息」（C014-D）：C 级资料的真实值写进 {@code storage/settings/personal.env}，资料里只登记占位符
 * {@code {KEY}}。任何返回里都没有值，只有「已设置 / 未设置」。写接口和别的设置一样要求 {@code application/json}
 * （{@link LocalRequestGuard}）。
 */
@RestController
@RequestMapping("/api/settings/personal")
public class PersonalSettingsController {

    /** @param label 自己加的键的名字（预设的键不用） */
    public record ValueRequest(String value, String label) {
    }

    private final PersonalSettingsService settings;

    public PersonalSettingsController(PersonalSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public PersonalSettingsService.View view() {
        return settings.view();
    }

    @PutMapping(path = "/{key}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PersonalSettingsService.Row set(@PathVariable String key, @RequestBody ValueRequest request) {
        return settings.set(key, request.value(), request.label());
    }

    @DeleteMapping("/{key}")
    public PersonalSettingsService.Row clear(@PathVariable String key) {
        return settings.clear(key);
    }
}
