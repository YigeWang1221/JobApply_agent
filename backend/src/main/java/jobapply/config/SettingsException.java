package jobapply.config;

/**
 * 设置相关的失败，按 HTTP 语义分开：{@link NotFound} 404，{@link Conflict} 409，{@link Upstream} 502（提供方那边出错）。
 * 信息里不带 Key。
 */
public abstract class SettingsException extends RuntimeException {

    private SettingsException(String message) {
        super(message);
    }

    public static final class NotFound extends SettingsException {

        public NotFound(String message) {
            super(message);
        }
    }

    public static final class Conflict extends SettingsException {

        public Conflict(String message) {
            super(message);
        }
    }

    public static final class Upstream extends SettingsException {

        public Upstream(String message) {
            super(message);
        }
    }
}
