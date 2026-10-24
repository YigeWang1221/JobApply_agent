package jobapply.persistence;

/**
 * 写 Job 和 Session 文件（{@code job.md}、{@code events.jsonl}、{@code session.md}、{@code transcript.jsonl}）的同一把锁
 * （C006-fix）。{@link JobLedger}、{@link SessionRepository}、{@link JobMaterials}、{@link JobAnswers} 在各处都是
 * 各自 new 的，所以锁不能放在实例上：一个进程里的所有实例共用这一把，读—改—写不会互相覆盖。可重入。
 */
final class StorageLock {

    static final Object WRITES = new Object();

    private StorageLock() {
    }
}
