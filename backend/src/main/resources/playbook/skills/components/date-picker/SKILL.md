---
name: date-picker
description: 日期和年月控件：先用 browser_set_date 一次设好；按控件分原生、能键入、只读面板三种；没设上时怎么在面板上跳年份和月份。
applies_to:
  url_patterns: []
  page_markers:
    - ant-picker
    - ant-calendar
    - el-date-editor
    - el-picker-panel
    - react-datepicker
version: 3
observed_count: 0
---

# 日期与年月选择

## 先用 browser_set_date

- 一个日期一次调用：target 写日期框（快照里的 textbox / combobox，没有名字时按前面的标签文字认），date 写 `YYYY-MM-DD`；字段只要年月就写 `YYYY-MM`，只要年份写 `YYYY`。不要自己补一个日：资料只有年月、框却要到日时，系统会返回 need_day（见下面）。
- 系统按控件处理，结果里会写是怎么设的、读回的值对不对得上：
  - **原生控件**（input type=date / month）：直接设 ISO 格式的值。
  - **能键入的框**：按占位符的格式（YYYY-MM、YYYY/MM/DD、MM/YYYY 等）键入，再按 Tab 失焦，读回显示的值。
  - **只读的框**（readonly，点开才出日历面板，Ant Design 3、Element 不可编辑的日期框常这样）：点开面板；面板里有输入框就在那里键入；否则点头部的年份 / 月份切到年份表、月份表，一次跳到目标年月，再点日期。不按「上一年」一年一年翻。
- 范围控件（开始 ~ 结束）：开始、结束各调用一次，target 分别写两个框。

## 结果怎么处理

- 「读回的值对不上」：看新快照里这个框显示的是什么。常见是格式差异（2025-09 和 09/2025）；真的不对时再调一次，或者手动在面板上点。
- 「面板上还有『确定』」：有的面板要按确定才生效。系统的脚本不点它，由你在新快照里找到面板上的「确定」，用 browser_click 点（purpose 写 confirm，final_step_suspected 写 false），再看新快照核对。
- 「need_day」：这个框要精确到日，你给的只有年月。面板翻到过那一年那一月、没有点任何日期，之后系统按 Escape 把面板关上（原生 date 框没有动）。不要自己编一个日：把这一页这样的日期攒起来，用一次 ask_user 问用户各是哪一天；用户回答后用 browser_set_date 写完整的 `YYYY-MM-DD` 再设一次。
- 「没找到」：结果里有面板现在的样子（日期表 / 月份表 / 年份表、头部文字、格子）。照它自己点：点头部的年份文字切到年份表，点年份，再点月份，再点日；年份表里没有目标年份时点它的上一页 / 下一页（十年一翻）。
- 「这是只读的显示框」（键入前系统检查出来的）：不要再用 browser_type / browser_fill_form 往里键入，改用 browser_set_date。
- 面板关不掉时按一次 Escape 或点一下空白处就够，不反复按 Escape。

## 经验

- 「至今」「Present」通常是一个单独的复选框或面板上的按钮，勾上后结束时间会变成不可填。资料里写的是在读或在职时才用它。
- 日期以资料为准，不能自行加减一天或一个月。页面显示的值和资料不一致时，先看是不是格式差异，再看是不是控件按时区或月份边界做了偏移。
- 有的控件只有失焦后才把值写进表单；browser_set_date 键入后已经按过 Tab。字段旁边仍有必填错误时，聚焦后再按一次 Tab。
- 保存后回读：重新进入这一步或刷新，看值还在不在，格式对不对。
