# C029 · 后端内存：给 JVM 设上限，嵌入模型用时才加载、闲了就卸下

- 对应：Master [architecture.md](../../master/architecture.md)（后端进程）；相关：[C017](../03-features/C017-macos-app.md)（macOS App 外壳怎么启动后端）、[C022](C022-workspace-followup-and-rag.md)（经验检索的本地嵌入模型）。
- 依据：用户（2026-11-14）：App 跑起来 JVM 占约 1GB。先实测内存去了哪，再写计划压下来。嵌入模型已经是 int8（`model_quantized.onnx` 约 118MB），不再提量化。
- 状态：**已确认，已施工**（2026-11-14），等用户重新打包 App 后在本机看。

## 目标

App 平时（没在建索引、没在检索）后端占用从约 1.1GB 降到约 250–300MB；建索引 / 检索时临时升到约 1GB，用完几分钟后降回来。经验检索的结果不变。

## 不做

- 不换嵌入模型、不再量化、不换向量库。
- 不自己重写分词器（见「实测」第 3 条，分词器是最大头，但重写风险大；先看本计划做完后的数字再说）。
- 不改 Agent、浏览器、MCP 相关的任何东西。

## 实测（2026-11-14，本机 M 系列 16GB / 10 核，JDK 25 Homebrew）

做法：`mvn -B -q -DskipTests package` 打出的 jar，`java -XX:NativeMemoryTracking=summary [参数] -jar … --server.port=8090 --jobapply.storage.root=<scratchpad 临时目录>`。**用默认 profile**（macapp profile 要外壳传 `JOBAPPLY_RESOURCES` 和 stdin 管道，脱离外壳不好起；内存相关的部分两者一样）。临时目录里放了 3 个假的 `review/` 复盘文件（约 60 个片段，无个人信息）；「带模型」= 模型文件已在 `model/RAG/`（经 App 自己的 `POST /api/rag/download` 从 Hugging Face 下载），启动后后台建好索引再量。RSS 用 `ps`，「占用」是 `vmmap -summary` 的 Physical footprint（≈ 活动监视器的「内存」）。

### 1. 默认参数（App 现在的样子）

JVM 自己选的：G1、`MaxHeapSize` 4GB（内存 1/4）、`InitialHeapSize` 256MB、代码缓存 240MB。

| 场景 | RSS | 占用 | NMT 合计已提交 | 堆 已提交 / 已用 | GC | Metaspace | 代码缓存 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 空闲（没有模型） | 350MB | 328MB | 292MB | 132 / 90MB | 54MB | 46MB | 19MB |
| 带模型（建完索引） | 1155–1187MB | 1.0GB | 413MB | 248 / 125MB | 57MB | 47MB | 20MB |

带模型时 RSS 比 NMT 多出约 **740MB**，是 JVM 管不到的本机内存（ONNX Runtime 和分词器的 Rust 库）。

### 2. 只改 JVM 参数（再量同样两个场景）

| 参数 | 空闲 RSS / 占用 | 带模型 RSS / 占用 |
| --- | --- | --- |
| 默认 | 350 / 328MB | 1155 / 1.0GB |
| `-Xmx256m` | 未运行 | 1154MB / 未量 |
| `-Xmx256m -XX:+UseSerialGC` | 未运行 | 1151MB / 未量 |
| `-Xms64m -Xmx384m -XX:+UseSerialGC` | 344 / 322MB | 1226 / 1.1GB |
| `-Xms64m -Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=64m` | **258 / 238MB** | 1168 / 1.1GB |
| `-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=64m -Xss512k -XX:MaxMetaspaceSize=160m` | 279MB / 未量 | 1057MB / 未量 |

所有组合都正常启动、模型加载、索引建好（`/api/rag/status` 为 ready、「索引已和记录对上」），日志里没有 OutOfMemory。结论：JVM 参数能把空闲压掉约 90MB（主要来自只用 C1 编译器、代码缓存小），堆上限和 GC 换哪个对 RSS 影响不大；**带模型时仍在 1.1GB 上下，大头不在 JVM 里**。

### 3. 模型那 740MB 是谁（单独的小程序，只加载分词器和 ONNX 会话）

| 步骤 | RSS |
| --- | --- |
| JVM 起来 | 43MB |
| 加载 `tokenizer.json`（DJL HuggingFaceTokenizer） | 547MB（**+504MB**；占用约 485MB，其中 malloc 在用 287MB） |
| 只加载一个两词的小分词器（对照） | 50MB（占用 29MB），说明是 25 万词表的分词器本身，不是库 |
| 创建 ONNX 会话（`model_quantized.onnx`） | 745–763MB（+200MB） |
| 跑 60 次嵌入（最长 512 词元） | +28MB |

会话选项（关内存池 `setCPUArenaAllocator(false)`、关内存模式、单线程、BASIC 优化）几乎没差别（±20MB）；`session.disable_prepacking=1` 只省下推理时的 28MB。
**关掉分词器和会话后**：占用 806MB → **279MB**（RSS 因为 macOS 的懒回收仍显示 580MB，活动监视器看的是占用）。

### 4. 为什么用户每次打开都是 1GB

`ExperienceIndex.start()` 在模型文件已在本地时马上 `requestSync()`，`sync()` 第一行就调 `model.embedding()`，于是**每次启动 2 秒后就把分词器和 ONNX 会话加载进来，一直留着**，哪怕索引早就和文件对上、这次根本不用嵌入。

## 要改的地方

1. **JVM 参数**（三个平台一样）：`-Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=64m`。
   - `appbuild/macos/JobApply/main.swift`：`p.arguments` 在 `-jar` 前加这四个。
   - `appbuild/linux/build.sh`、`appbuild/windows/build.ps1`：各加四行 `--java-options`。
   - 堆用 384m 不用 256m：实测带模型时堆用到约 125MB，用户真实数据的向量索引和 Agent 运行时的对话会更多，留余量；真不够时日志会报 OutOfMemoryError（失败可见）。
2. **嵌入模型用时才加载、闲了卸下**（`jobapply.memory`，小改）：
   - `ExperienceModel.embedding()` 不再直接给 `E5Embedding`，给一个轻的 `EmbeddingModel` 包装：第一次真要算向量（`embed` / `call`）时才加载分词器和会话；最后一次使用后闲置约 5 分钟（常量，不进设置页）就 `close()` 掉，下次用再加载（实测加载约 1–2 秒）。
   - 这样启动时索引已对上就不会加载；有文件变了、或 Agent 调 `search_experience` 时才加载，用完自己降回去。`SimpleVectorStore` 拿的是这个包装，不用跟着重建。
   - 加载失败的处理不变（状态变 `failed`、退回关键词检索）。
3. 不改：ONNX 会话选项（实测几乎无差别）、分词器实现。

预期（按上面的实测推算，做完后要再量）：平时约 250–300MB；建索引 / 检索时约 1GB；闲置 5 分钟后回到约 300MB 上下（卸下后有一部分本机内存不完全还给系统，实测单独小程序里剩约 280MB）。如果做完发现卸下后回落不够，再考虑把嵌入放进单独的子进程（用完就退出）——那是更大的改动，到时另问。

## 怎么确认能用

1. `backend/` 里 `mvn -B clean verify` 退出码 0。
2. 用改后的参数照「实测」的做法重跑四个场景，填进本文件：空闲、启动时模型已在本地但索引已对上（应**不加载**模型）、改一个复盘文件触发建索引（加载）、闲置 5 分钟后（卸下）。记 RSS、占用、`jcmd VM.native_memory summary`。
3. 重新打包 macOS App，打开后在活动监视器看 JobApply 的 java 进程「内存」；在工作台让 Agent 用一次经验检索，确认能搜到、没有 OutOfMemory（用户在本机试）。

## 施工记录（2026-11-14）

改动：
- `backend/src/main/java/jobapply/memory/ExperienceModel.java`：`embedding()` 在状态 ready 时返回一个固定的轻包装 `LazyEmbedding`（`embed(String)` / `embed(Document)` / `call` 都走 `use()`，`dimensions()` 直接给 384 不加载）。`use()` 在 `useLock` 里：没加载就加载、算完重新排一次卸下任务（下载线程改成 `ScheduledExecutorService`，复用它）。卸下任务也拿 `useLock`，所以不会和正在算的向量撞上。加载失败：状态 `failed`、删坏文件、抛 `IllegalStateException` 给调用方（建索引记进 `lastError`，检索退回关键词并说原因），不再静默返回 null。锁顺序固定为 `useLock` → 对象本身。
- `backend/src/main/java/jobapply/config/RagConfiguration.java`：闲置时长读 `jobapply.rag.model-idle`，默认 `5m`（只为测试能缩短，不进设置页）。
- `ExperienceIndex` 不用改：`sync()` 只在有文件变了时才 `store.add` 算向量，读回旧索引不需要模型。
- `appbuild/macos/JobApply/main.swift`（`p.arguments`）、`appbuild/linux/build.sh`、`appbuild/windows/build.ps1`（`--java-options`）：加 `-Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=64m`。注意 `appbuild/` 在 `.gitignore` 里，这三处改动不进 Git。

验证：
- `backend/` `mvn -B clean verify`：退出码 0（单元 668 + 场景 1，0 失败）。`swiftc -parse main.swift`、`bash -n build.sh` 通过；`build.ps1` 未运行（本机无 PowerShell）。
- 照「实测」的做法（默认 profile、8090、scratchpad 临时 storage、NMT、新参数、`--jobapply.rag.model-idle=60s`）：

| 场景 | 改前（默认参数） RSS / 占用 | 改后 RSS / 占用 |
| --- | --- | --- |
| 启动，模型在本地、索引已对上 | 1155–1187MB / 1.0GB（启动即加载） | **319MB / 299MB**，日志里没有加载 |
| 改了一个复盘文件，启动后建索引 | 同上 | 1092MB / 1.0GB，状态 ready、索引已对上 |
| 闲置 60s 卸下后 | 不卸下 | 929MB / **597MB** |
| 空闲、没有模型 | 350MB / 328MB | 258MB / 238MB（实测第 2 节） |

- 卸下后再算：scratchpad 小程序直接用 `ExperienceModel`（闲置 2 秒）：第一次算向量时加载、2 秒后卸下、再算时重新加载，向量和第一次完全一样；4 个线程在闲置边界附近反复算 40 秒，79 次成功、0 次失败。
- 卸下后占用只回到约 600MB，没到预期的约 300MB：分词器 / ONNX 关掉后有约 300MB 本机内存没还给系统（单独小程序里也一样）。平时启动不加载这一点已经达到；若要卸下后完全回落，需要把嵌入放进子进程（更大的改动，待用户决定）。
- 未运行：打包 macOS App 并在活动监视器里看；Linux / Windows 打包；Agent 真实调用 `search_experience`（会调 LLM）。
