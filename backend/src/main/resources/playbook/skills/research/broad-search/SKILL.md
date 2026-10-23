---
name: broad-search
description: 撒网调研怎么想搜索词：从画像和偏好拆出方向、城市、岗位类型，组合成多组短查询，读 JD 原文再放进候选池。
applies_to:
  url_patterns: []
  page_markers: []
version: 1
observed_count: 0
---

# 撒网调研

## 先拆条件

从画像（profile.md）和偏好（preferences.md）里取：

- **方向**：画像里的主要方向和技能（例如后端、数据平台、AI 应用），每个方向想 2–3 个常见岗位名（「后端开发工程师」「服务端研发」「Backend Engineer」）。
- **城市**：偏好里写了就用；没写时不加城市，或者问用户。
- **类型和届别**：校招 / 实习 / 社招，毕业年份（「2027 届」「2027 校招」）。
- **硬条件**：学历、毕业时间、经验年限、语言。读 JD 时用来初步核对。

## 组合查询

- 一个查询只放 2–4 个词，宁可多搜几次：`后端开发 校招 上海 2027`、`<公司名> 校园招聘 后端`、`<公司名> careers backend new grad`。
- 想到具体公司时，直接搜「公司名 + 校园招聘 / careers」，找官方招聘站，比搜聚合站更可靠。
- 海外公司常用 ATS：Greenhouse（`boards.greenhouse.io/<company>`）、Lever（`jobs.lever.co/<company>`）、Ashby（`jobs.ashbyhq.com/<company>`）。它们有公开接口，可以直接 web_fetch 列表：
  - `https://boards-api.greenhouse.io/v1/boards/<company>/jobs`
  - `https://api.lever.co/v0/postings/<company>?mode=json`
  - `https://api.ashbyhq.com/posting-api/job-board/<company>`
- 搜索被限流或没结果：换一个搜索来源（web_search 的 provider）、换个说法，或者直接用 web_fetch / 浏览器去招聘站、搜索引擎的结果页。

## 读结果

- 搜索结果只是线索。打开（web_fetch）看起来是具体岗位的链接，读 JD 原文再判断。
- 招聘站、聚合站上的岗位都可以放；过期的往年岗位不放。能找到官方链接时优先用官方链接，或者之后做官网核验。
- 初步核对：方向对不对、城市对不对、学历和毕业时间是否符合、要求几年经验。拿不准的也可以放，note 里写清楚要核对什么。
- 同一家公司找到多个岗位时都可以放，告诉用户时按公司归在一起。

## 放进候选池

- add_lead：公司、岗位名（用页面原文）、链接（读到过的那个）、城市、类型；note 用一两句话写为什么合适、哪里要核对。
- 放之前先 list_jobs，已经有的不重复放。
- 一轮下来告诉用户：搜了哪些方向、放进几个、哪些拿不准、下一步建议（例如对哪几个做官网核验）。
