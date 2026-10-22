/**
 * storage 读写：Markdown + YAML frontmatter、JSONL 事件、可选的 storage/.git，以及生成的台账。
 * Job 的写操作统一经过 {@link jobapply.persistence.JobLedger}。
 */
package jobapply.persistence;
