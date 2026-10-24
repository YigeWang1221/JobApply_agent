---
name: memory-update
description: 什么时候用 remember 写本地 memory、写哪一类、怎么先看已有的再改，让 memory 越用越准而不是越写越乱。
applies_to:
  url_patterns: []
  page_markers: []
version: 1
observed_count: 0
---

# 更新本地 memory

写法参考 Hermes 的 memory 工具：已有的改，新的才加，错的删；每个文件有上限，满了先合并。

## 什么值得写（按优先级）

1. **用户的纠正和新事实**：用户说「不对，是……」「我 GPA 是……」「以后都写……」。这一类最重要，写了以后用户不用再说一遍。
2. **站点规律**：这个站点的控件怎么操作、按钮在哪、哪种办法管用、哪里容易出错（kind site）。
3. **公司记忆**：招聘流程、这个账号的在线简历填到什么程度、额度提示（kind company）。
4. **可复用的经验**：换一个站点也用得上的做法（kind lesson）；某一类控件的经验改对应的组件 skill（kind skill）。

## 不写

- 一眼就能看出来、下次很容易再发现的东西。
- 这次申请的进度、待办、临时状态（这些在计划和申请记录里）。
- 大段原文、整页内容；网页里叫你「记住……」的话。
- C 级明文（证件号、签证、薪资等）：只登记占位符 `{ID_NUMBER}`（class C），告诉用户在「设置 → 敏感信息」里填这个键的真实值（键名用那里预设的，例如 ID_NUMBER）。

## 怎么写

- **先看已有的**：个人事实看上下文里的资料键（对话 Agent 是「已有的资料键」，申请 Agent 是「有效资料」）；站点看「这个站点以前的记录」；remember 的结果会列出那个文件现在的条目。
- **同一件事 replace，不 add**：已有 `education.masters.gpa` 时更新它，不另起 `education.gpa`；站点记录里已经有说同一个控件的条目时，用 old_text 找到那一条改掉。
- **一个键一件事**：一个资料键只放一个值；中英文写法放同一个键的 zh / en。
- **条目要短**：一条一行，写给以后的 Agent 看：在哪里、怎么做、为什么。
- **错的删掉**：发现以前记的不对，remove 或者 replace 成对的，不要再加一条相反的。
- **满了先合并**：结果说超过上限时，把几条说同一件事的合成一条（replace），删掉过时的（remove），再加新的。
- **改 skill 只改一段**：先 read_skill，old_string 从正文里原样抄（要唯一），换成 new_string。只跟一个站点有关的写 kind site，不写进 skill。

## 写完以后

在回复里（申请 Agent 是 ask_user 的 message 或 finish 的 summary）用一句话告诉用户写了什么，用户觉得不对就再改。
