package jobapply.domain;

import java.util.Map;

/**
 * 论断台账（{@code base/ledger.md}）的一行。台账是整理用的内部材料，论断用中文写；对外的文字在事实层成对保存。
 * 出处、值与条件、确认时间都可以留空（memory.md「来源与使用：用户确认优先」）。
 *
 * @param extra 未识别的列（列名 → 值），读写时原样保留
 */
public record Claim(String id, String category, String text, String source, String locator, String valueAndCondition,
                    Status status, Use use, String confirmedAt, Map<String, String> extra) {

    public enum Status {
        VERIFIED, PARTIAL, OWNER_ATTESTED, DISCREPANCY, NEEDS_VERIFICATION;

        public String code() {
            return name().toLowerCase();
        }

        public static Status of(String code) {
            for (Status status : values()) {
                if (status.code().equals(code)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("不认识的论断状态：" + code);
        }
    }

    public enum Use {
        FORMS_AND_RESUME, INTERVIEW_ONLY, NONE;

        public String code() {
            return name().toLowerCase();
        }

        public static Use of(String code) {
            for (Use use : values()) {
                if (use.code().equals(code)) {
                    return use;
                }
            }
            throw new IllegalArgumentException("不认识的论断用途：" + code);
        }
    }

    public Claim {
        if (id != null && !Ids.isClaimId(id)) {
            throw new IllegalArgumentException("论断 id 不合法（应为 c-NNNN）：" + id);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("论断不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("论断必须有状态");
        }
        use = use == null ? defaultUse(status) : use;
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /**
     * 默认用途：用户确认过的（{@code verified}、{@code partial}、{@code owner_attested}，包括指标）可以填表和写简历；
     * {@code needs_verification} 还在等用户确认，先不用；{@code discrepancy} 不自动禁用，沿用可以填表、写简历，
     * 由用户在差异处理时选择采用的版本和用途。
     */
    public static Use defaultUse(Status status) {
        return status == Status.NEEDS_VERIFICATION ? Use.NONE : Use.FORMS_AND_RESUME;
    }

    /** 还没有编号的新论断，由 {@code LedgerFile} 编号。 */
    public static Claim draft(String category, String text, String source, String locator, Status status) {
        return new Claim(null, category, text, source, locator, null, status, null, null, null);
    }

    public Claim withId(String newId) {
        return new Claim(newId, category, text, source, locator, valueAndCondition, status, use, confirmedAt, extra);
    }
}
