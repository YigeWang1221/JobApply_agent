/**
 * 事件、追踪与审计。评测模式（C025）的记录在这里：{@link jobapply.observability.EvalOptions} 是一次评测运行的设置，
 * {@link jobapply.observability.EvalRecorder} 把每步脱敏快照、拦下的提交请求和 {@code meta.json} 写进
 * {@code storage/eval/}。评测关着时这里的类都不会被用到。
 */
package jobapply.observability;
