package jobapply.persistence;

/**
 * 同一 Project 的候选池里已有「公司 + 招聘项目 + 岗位 ID」相同的岗位。
 */
public class DuplicateLeadException extends RuntimeException {

    private final String existingJobId;

    public DuplicateLeadException(String existingJobId) {
        super("候选池里已有同一岗位：" + existingJobId);
        this.existingJobId = existingJobId;
    }

    public String existingJobId() {
        return existingJobId;
    }
}
