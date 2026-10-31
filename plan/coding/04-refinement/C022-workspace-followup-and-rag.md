# C022 · 工作台「后续」与跨工作台经验检索（RAG）

- 对应 Master：[tracking.md](../../master/tracking.md)（阶段、轮次、复盘、题库）、[memory.md](../../master/memory.md)、[agent-runtime.md](../../master/agent-runtime.md)、[ui.md](../../master/ui.md)
- 依据：用户（2026-10-28）：
  1. 投递完成后工作台不结束，切到「后续」，继续聊 OA 题、面试题等；Session 里的后续入口全部搬进工作台，Session 只做找岗位和调研。
  2. 「后续」是一段新对话，界面上仍是同一个工作台，避免投递过程的内容影响后续。
  3. 工作台的经验不能只留在单个工作台：引入 RAG，让不同工作台（包括跨 Session 的）互相用得上，只针对「后续」和「前期投递优化」的内容。
  4. `storage/example.data/` 已删除，不进检索范围。
- 状态：**已施工，等用户在本机试**（2026-10-28）。JDK 25 下 `mvn -B clean verify` 退出码 0，`npm run build` 退出码 0。真实模型和真实 Chrome 下的后续段对话**未运行**。后续段带只读浏览器工具（用户同意）。

## 不做

- 不新增确认断点和守卫。「系统不点最终提交」「C 级真实值不进模型上下文」两条线不变。
- 检索不覆盖 Session 调研对话、`profile.md` 和密钥文件，也不覆盖已删除的 `example.data`。
- 不做面试准备清单的固定流程。怎么准备由模型借助检索和工具自己决定。

## 1. 工作台「后续」模式

- **什么时候进入**：申请离开 prep 阶段，也就是确认已投递（`applied`）之后，或者后来记到了 OA、面试等轮次。工作台输入框不再锁住（去掉 `JobPage.tsx` 里的 `lockedReason`），页面顶部显示当前阶段。
- **对话分段**：投递段就是现有的 `AgentRun`，变成只读，可以折叠。后续段单独存一份对话，放在 `jobs/<id>/followup/`。界面上两段连在一起，中间一条分隔线：「已投递 · 后续」。
- **后续段的开场上下文**（不带投递段的历史）：
  - 用的简历（`resume_choice` 对应的原件）
  - JD 快照
  - `lead_note`
  - 当前阶段和已有轮次的摘要
  - 这家公司的复盘（`review/companies/<co>.md`）

  其余内容由模型按需检索。
- **工具**：把 `mark_submitted`、`record_status`、`record_round`、`save_review` 从 Session agent 挪到工作台。后续段另外带上 `search_experience`（见第 2 节）和读简历、读 JD 的工具，不带浏览器填表工具。需要时可以打开浏览器只读查看邮箱或官网状态，这一条施工时按现有浏览器工具的划分再定。
- **Session**：去掉上面这些后续工具，提示词改成只管找岗位和调研。C007 的查看提醒改成跳到对应的工作台。

## 2. 跨工作台经验检索（RAG）

- **索引什么**：
  - 各工作台的轮次记录 `jobs/*/rounds/*.md`
  - 复盘：`review/questions.md`、`review/companies/*.md`
  - 填表报告 `fill-report.md`，里面是表单问题和当时给的回答
  - 后续段对话里由模型存下的要点，通过 `save_review` / `record_round` 写入，不索引整段对话原文

  每个片段带上出处（工作台 id、公司、岗位、阶段、日期）作为元数据。
- **怎么建索引**：
  - 用 Spring AI 的 `VectorStore` 抽象。先用 `SimpleVectorStore`，存成 storage 下的一个文件（例如 `cache/rag/index.json`），不另起服务。
  - 上面这些文件写入时增量更新索引。启动时发现索引缺失或和文件对不上，就重建。
- **怎么用**：工作台的两段都有 `search_experience(query, filters?)` 工具，可以按公司、阶段、类型过滤，返回片段和出处。
  - 投递段：填开放式问题、写 Cover Letter 时可以查以前类似问题的回答。
  - 后续段：准备 OA 或面试时查同公司、同岗位类型的历史题目和复盘。

  什么时候查、查什么由模型决定，提示词和 skill 里写明这个工具的用途。
- **嵌入模型**（用户定，2026-10-28）：本地 ONNX，在后端进程里跑，依赖 Spring AI 的 `spring-ai-transformers`，不联网，也不调云端厂商。
  - **模型**：默认用多语言小模型 `multilingual-e5-small`（约 100MB，中英混合的内容效果稳）。施工时如果发现它的中文效果明显不如 `bge-small-zh-v1.5`，可以换掉，并在本文件里记下原因。
  - **下载**：不随 App 打包。第一次用到时下载到 `storage/model/RAG/`，包括模型文件和分词器。下载时界面上显示进度。下载失败就把错误告诉调用方，这时检索退回关键词检索，不让检索静默失效。
  - 以后已经下载过就直接读本地文件，不再联网。

## 施工分工（multi-agent，用户要求）

- **工作包 A · RAG（后端 + 设置页一小块）**：嵌入模型下载、索引、检索服务、`search_experience` 工具定义与执行、`GET /api/rag/status`（`state`: absent / downloading / ready / failed，`progress`，`message`），设置页显示模型状态和下载进度。在轮次、复盘、fill-report 的写入处挂增量索引。只改 RAG 相关的新文件、`pom.xml`、写入类和设置页；不碰 Agent 循环、提示词和 JobPage。
- **工作包 B · 工作台后续（后端 + JobPage）**：后续段的存储、开场上下文、后续 agent 与提示词，工具从 Session 搬进工作台，Session 提示词收窄，提醒跳工作台，JobPage 解锁、分段和分隔线。不碰 `pom.xml`，也不碰 RAG 文件。
- **合龙（主代理）**：把 `search_experience` 注册进工作台投递段和后续段，跑 `mvn -B clean verify` 和 `npm run build`，回写 Master。
- 两个工作包各在自己的 git worktree 里施工，合龙时合并。

## 施工记录（2026-10-28）

### 工作包 B · 后续段
- **存储**：`jobs/<id>/followup/` 下有 `transcript.jsonl`（和 Session 的 transcript 同一种行格式）、`followup.md`（最近的状态和错误）、`attachments/`。不写进 `runs/<job>.json`。
- **循环**：`FollowupLoop` 用现有的 `AgentLoop`。`FollowupRunner` 照 `SessionRunner` 的做法：后台线程池、收件箱（跑着时也能插话）、停止、重启后标为中断。提示词在 `FollowupAgent`，工具在 `FollowupTools`。
- **只读浏览器**：从 `SessionLoop` 抽出 `ReadOnlyTab`，Session 和后续段共用。工具有 navigate、snapshot、find、click、wait_for、tabs，不能输入、填表、上传；click 和调研标签页一样有提交守卫。
- **接口**：`/api/jobs/{id}/followup` 下有 `GET`、`POST /messages`、`POST /attachments`、`GET /attachments/{attachmentId}`、`POST /stop`。`GET /api/jobs/{id}` 多一个 `followup` 字段。推送沿用 `/api/events?job=`。
- **Session**：去掉 `record_status`、`record_round`、`save_review`、`mark_submitted`，提示词收窄。C007 的「我看了」改成打开工作台，并预填后续段的草稿。
- **和计划不同的地方**：
  - `mark_submitted` 放在投递段，不在后续段：后续段只在已投递之后才有，放在那里用不上。在投递段里，用户说「投了」就记为已投递，运行不中止。
  - 后续段没有 `web_search` / `web_fetch`。
  - 后续段不能重新生成。

### 工作包 A · RAG
- **文件**：`memory/` 下新增 `ExperienceModel`（下载、进度、懒加载）、`E5Embedding`、`ExperienceSources`（找文件、切块、元数据）、`ExperienceIndex`（索引、同步、检索）。另有 `tools/ExperienceTools`、`config/RagConfiguration`、`api/RagController`（`GET /api/rag/status`、`POST /api/rag/download`）、设置页 `RagSettings.tsx`。
- **模型**：`Xenova/multilingual-e5-small` 的量化 ONNX，固定在一个提交上。模型约 118MB，`tokenizer.json` 约 17MB，按大小和 SHA-256 校验。第一次检索或点下载时下载到 `model/RAG/`。
- **没用 `TransformersEmbeddingModel`**：它的池化要靠 DJL 的 PyTorch 引擎，运行时会从 DJL 的服务器下载 libtorch，下载位置不在 storage 下，也没有进度，违背「只下载一次、之后不联网」。所以改为只用 ONNX Runtime 和分词器，池化用 Java 写。pom 排除了 pytorch-engine、model-zoo，以及会覆盖 Tika 版本的旧 commons-compress。打包后 App 大约增大 150MB（onnxruntime 带了全平台的本地库）。
- **索引**：Spring AI 的 `SimpleVectorStore`，存为 `cache/rag/index.json`；`files.json` 记每个文件的哈希。三个时机会同步：启动时在后台同步；写入后 2 秒增量同步；每次检索前快速同步一次。写入通知用 `StorageWrites`（进程内静态通知，因为写入类到处都是 `new` 出来的）。fill-report 只索引字段表，跳过 C 级「（用户已填）」的行。
- **退回关键词检索**：模型还没下载、下载失败，或者后台正在重建时，退回关键词检索，结果开头注明「检索方式：关键词检索（原因）」。
- **验证**：工作包 A 用一次性的检查（提交前已删除）跑过真实下载，确认了这几项：
  - 语义检索可以用英文查中文记录；
  - 按公司、阶段、类型过滤都有效；
  - 新写的内容 2 秒内进入索引；
  - 重启后索引能从磁盘读回；
  - 下载地址错误时状态显示 failed，并退回关键词检索。

  设置页只过了 `npm run build`，没在浏览器里看过。

### 合龙
- **共用工具**：`search_experience` 登记在 `harness/runtime/WorkbenchTools`，投递段和后续段都经过这里。`ExperienceIndex` 的传递路径是：`ApplicationConfiguration` → `ApplicationRunner` → `HarnessLoop`；`FollowupRunner` 通过 `applications.experience()` 拿到同一份，再传给 `FollowupLoop`。
- **过滤**：不按当前公司默认过滤，过滤条件由模型自己给，这样跨公司的经验也能查到。

## 3. 文档回写（用户已确认，已完成）

- **tracking.md**：后续对话的入口从 Session 改为工作台。
- **memory.md**：加上 `jobs/<id>/followup/`、RAG 索引的位置，以及模型目录 `storage/model/RAG/`。
- **agent-runtime.md**：工作台的两段对话、工具的划分、`search_experience`。
- **ui.md**：工作台的后续段，Session 的职责。

## 怎么确认能用

- 构建：`mvn -B clean verify` 和 `npm run build` 都通过。
- 本机试，由用户来跑：
  - 一份已投递的申请打开工作台，能在后续段继续聊天。
  - 说「一面问了 X」，会记成一轮，题目进入题库。
  - 换另一个 Session 下的工作台准备面试时，`search_experience` 能查到前面那家记下的题目。
