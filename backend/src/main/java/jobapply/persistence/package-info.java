/**
 * storage 读写：Markdown + YAML frontmatter、JSONL 事件，以及生成的台账。storage 不做版本控制。
 * Job 的写操作统一经过 {@link jobapply.persistence.JobLedger}。
 */
package jobapply.persistence;
