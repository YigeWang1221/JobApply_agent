package jobapply.memory;

/**
 * 无法得到 Project 的有效资料：循环继承、父 Project 缺失、base_pin 找不到对应版本等。
 */
public class ProfileResolutionException extends RuntimeException {

    public ProfileResolutionException(String message) {
        super(message);
    }
}
