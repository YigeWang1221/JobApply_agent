/**
 * 申请线程：开始、回复、停止、状态、报告。流程本身由 {@link jobapply.harness.runtime.HarnessLoop} 里的模型决定，
 * 这里只负责排队、存取 {@link jobapply.harness.runtime.AgentRun}、更新台账和准备上传的文件。
 */
package jobapply.workflow;
