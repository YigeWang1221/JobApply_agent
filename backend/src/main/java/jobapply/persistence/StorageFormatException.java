package jobapply.persistence;

/**
 * storage 里的文件格式不对。消息里带上出错的文件。
 */
public class StorageFormatException extends RuntimeException {

    public StorageFormatException(String source, String message) {
        super(source + "：" + message);
    }

    public StorageFormatException(String source, String message, Throwable cause) {
        super(source + "：" + message, cause);
    }
}
