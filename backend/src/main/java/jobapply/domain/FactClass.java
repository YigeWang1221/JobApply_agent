package jobapply.domain;

/**
 * 字段分级。C 级（证件、工作授权、薪资期望等）的值只能是占位符 {@code {KEY}}，明文不出现在任何文件里（C014-D）。
 */
public enum FactClass {
    A, B, C
}
