package jobapply.tools;

import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 系统经 {@code browser_run_code_unsafe} 代发的固定页面脚本（C016）：超时后的检查、填写前的只读检查、选下拉、设日期。
 * 模型不能调用 {@code browser_run_code_unsafe}，也给不了代码；脚本是这里写死的，参数只以 JSON 嵌进开头那一行
 * {@code const args = …;}（{@link #code}）。
 * <p>
 * 每个脚本 = 开头的参数 + 共用的页面函数 {@link #DOM}（在页面里执行：找弹出层、选项、读输入框状态）+ 自己的步骤。
 * 点击都用 Playwright 的真实点击，只落在触发元素和弹出层里的选项 / 日期格上；不点按钮（确定、提交、下一步），不按回车，
 * 关弹出层最多按一次 Escape。打开之后不固定地等：每 100 毫秒看一次，选项 / 格子出来就往下走（最多约 1.2 秒）。脚本自己有 15 秒的时限，比 MCP 调用的超时短。
 */
final class PageScripts {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 页面函数（传给 {@code page.evaluate}）：按 {@code op} 做一件只读的事，或者返回一个元素给 Playwright 去点。
     * 弹出层 = 可见的、{@code position: fixed / absolute} 的容器，里面有选项类元素（option、menuitem、treeitem、gridcell、
     * li、td）；不按组件库的类名认。
     * <p>
     * 「可见」照 Playwright 自己的规则：有大小、不是 {@code visibility: hidden}、不是 {@code display: none}；不看透明度
     * （C016 实跑后修正：antd 3 的下拉淡入时容器 opacity 是 0，按透明度算会读成「没有选项」「已经关了」）。
     * 开着还是关着先看触发元素的 {@code aria-expanded}（{@code op: 'open'}），没有这个属性时才看弹出层可不可见。
     */
    static final String DOM = """
            const DOM = (input) => {
              const shown = n => {
                if (!n || !n.isConnected || n.nodeType !== 1) return false;
                const b = n.getBoundingClientRect(), s = getComputedStyle(n);
                return b.width > 0 && b.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
              };
              const expandedOf = el => {
                if (!el || !el.isConnected) return null;
                const own = el.getAttribute('aria-expanded') !== null ? el : el.querySelector('[aria-expanded]');
                const box = own || el.closest('[role=combobox][aria-expanded],[aria-haspopup][aria-expanded]');
                const v = box ? box.getAttribute('aria-expanded') : null;
                return v === 'true' ? true : v === 'false' ? false : null;
              };
              const textOf = n => String(n.innerText || n.textContent || n.getAttribute('title') || '').replace(/\\s+/g, ' ').trim();
              const OPTION = '[role=option],[role=menuitem],[role=menuitemradio],[role=menuitemcheckbox],[role=treeitem],[role=gridcell],li,td';
              const STRONG = '[role=option],[role=menuitem],[role=menuitemradio],[role=menuitemcheckbox],[role=treeitem],[role=gridcell]';
              const BAD = 'button,[role=button],input,textarea,select,a[href]';
              const floating = n => {
                for (let p = n.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
                  const pos = getComputedStyle(p).position;
                  if (pos === 'fixed' || pos === 'absolute') return p;
                }
                return null;
              };
              const items = c => {
                const all = [...c.querySelectorAll(OPTION)].filter(o => shown(o) && !o.closest('thead'));
                return all.filter(o => !all.some(x => x !== o && o.contains(x))
                  && !o.matches(BAD) && !o.querySelector('button,[role=button],input,textarea,select'));
              };
              const popups = (el, min) => {
                const out = [];
                const seen = new Set();
                let n = 0;
                for (const o of document.querySelectorAll(OPTION)) {
                  if (++n > 4000) break;
                  if (!shown(o)) continue;
                  const c = floating(o);
                  if (!c || seen.has(c)) continue;
                  seen.add(c);
                  if (!shown(c) || c.closest('header,nav') || (el && (c.contains(el) || el.contains(c)))) continue;
                  const found = items(c);
                  if (found.some(i => i.matches(STRONG)) || found.length >= (min || 1)) out.push(c);
                }
                for (const d of document.querySelectorAll('[role=dialog],[role=alertdialog],[role=listbox],[role=menu],[role=tree],[role=grid]')) {
                  if (shown(d) && !out.some(c => c.contains(d) || d.contains(c)) && !(el && d.contains(el))) {
                    const pos = getComputedStyle(d).position;
                    const f = pos === 'fixed' || pos === 'absolute' ? d : floating(d);
                    if (f && !out.includes(f) && !(el && f.contains(el))) out.push(f);
                  }
                }
                return out.filter(c => !out.some(o => o !== c && o.contains(c)));
              };
              const columns = c => {
                const groups = [];
                for (const i of items(c)) {
                  const parent = i.closest('ul,ol,[role=listbox],[role=menu],[role=tree],[role=group],[role=grid],table') || i.parentElement;
                  let g = groups.find(x => x.parent === parent);
                  if (!g) groups.push(g = {parent, items: []});
                  g.items.push(i);
                }
                return groups;
              };
              const column = (c, level) => {
                const cols = columns(c);
                return cols.length === 0 ? null : cols[Math.min(level, cols.length - 1)];
              };
              const brief = n => n && {
                tag: n.tagName.toLowerCase(), role: n.getAttribute('role') || '', text: textOf(n).slice(0, 60),
                cls: String(typeof n.className === 'string' ? n.className : '').trim().slice(0, 80), position: getComputedStyle(n).position
              };
              const inputOf = el => el.matches('input,textarea') ? el : el.querySelector('input,textarea');
              const {op, el, pre, popup, level} = input;
              switch (op) {
                case 'trigger': {
                  const field = inputOf(el);
                  return {
                    native: el.tagName === 'SELECT',
                    multiple: !!el.multiple,
                    options: el.tagName === 'SELECT' ? [...el.options].map(o => ({label: o.label, value: o.value, disabled: o.disabled})) : [],
                    expanded: expandedOf(el) === true,
                    name: el.getAttribute('aria-label') || (el.labels && el.labels[0] ? textOf(el.labels[0]) : '')
                      || (field && field.getAttribute('placeholder')) || textOf(el).slice(0, 40),
                    editable: !!field && !field.readOnly && !field.disabled && field.type !== 'hidden' && shown(field)
                  };
                }
                case 'popups': return popups(el);
                case 'popup': {
                  const old = pre || [];
                  const ids = [el.getAttribute('aria-controls'), el.getAttribute('aria-owns')].filter(Boolean)
                    .flatMap(v => v.split(/\\s+/));
                  for (const id of ids) {
                    const t = document.getElementById(id);
                    if (t && shown(t) && items(t).length) return floating(t) && !floating(t).contains(el) ? floating(t) : t;
                  }
                  const now = popups(el).filter(c => !old.includes(c));
                  return now.length ? now[now.length - 1] : null;
                }
                case 'open': {
                  const ex = expandedOf(el);
                  return ex !== null ? ex : !!popup && shown(popup);
                }
                case 'level': {
                  const col = popup && shown(popup) ? column(popup, level) : null;
                  const cols = popup && shown(popup) ? columns(popup).length : 0;
                  return {columns: cols, texts: col ? col.items.map(textOf) : []};
                }
                case 'item': {
                  const col = popup && shown(popup) ? column(popup, level) : null;
                  const i = col ? col.items[input.index] : null;
                  return i && textOf(i) === input.text ? i : null;
                }
                case 'scroll': {
                  const col = popup && shown(popup) ? column(popup, level) : null;
                  if (!col) return false;
                  for (let s = col.items[0]; s && s !== popup.parentElement; s = s.parentElement) {
                    const st = getComputedStyle(s);
                    if (s.scrollHeight > s.clientHeight + 4 && /auto|scroll/.test(st.overflowY)) {
                      if (input.top) { const moved = s.scrollTop > 0; s.scrollTop = 0; return moved; }
                      const before = s.scrollTop;
                      s.scrollTop = before + Math.max(40, s.clientHeight * 0.8);
                      return s.scrollTop > before;
                    }
                  }
                  return false;
                }
                case 'colRect': {
                  const col = popup && shown(popup) ? column(popup, level) : null;
                  const n = col && (col.parent || col.items[0]);
                  if (!n) return null;
                  const b = n.getBoundingClientRect();
                  if (b.width <= 0 || b.height <= 0) return null;
                  return {x: b.left + b.width / 2, y: b.top + b.height / 2, height: b.height};
                }
                case 'columnsInfo': {
                  const cols = popup && shown(popup) ? columns(popup) : [];
                  return cols.map(c => {
                    const n = c.parent || c.items[0];
                    const b = n ? n.getBoundingClientRect() : {left: 0, top: 0, width: 0, height: 0};
                    return {texts: c.items.map(textOf), x: b.left + b.width / 2, y: b.top + b.height / 2, height: b.height};
                  });
                }
                case 'scrollItem': {
                  const col = popup && shown(popup) ? column(popup, level) : null;
                  const i = col ? col.items[input.index] : null;
                  if (!i || textOf(i) !== input.text) return false;
                  i.scrollIntoView({block: 'center'});
                  return true;
                }
                case 'search': {
                  const a = document.activeElement;
                  const ok = n => n && n.matches('input,textarea') && !n.readOnly && !n.disabled && shown(n);
                  if (ok(a)) return a;
                  const f = inputOf(el);
                  if (ok(f)) return f;
                  const inPopup = popup ? [...popup.querySelectorAll('input')].find(ok) : null;
                  return inPopup || null;
                }
                case 'shown': {
                  const f = inputOf(el);
                  const v = f && f.value ? f.value : '';
                  return (v || textOf(el)).slice(0, 200);
                }
                case 'inspect': {
                  const t = el.matches('input,textarea,select') || el.isContentEditable ? el
                    : (el.closest('label') && el.closest('label').control) || el;
                  const tag = t.tagName.toLowerCase();
                  const fillable = tag === 'textarea' || t.isContentEditable
                    || (tag === 'input' && !/^(checkbox|radio|file|submit|button|reset|image|hidden)$/i.test(t.type || ''));
                  return {tag, type: t.getAttribute('type') || '', role: el.getAttribute('role') || '',
                    readonly: !!t.readOnly || t.getAttribute('aria-readonly') === 'true',
                    disabled: !!t.disabled || t.getAttribute('aria-disabled') === 'true', nativeSelect: tag === 'select', fillable};
                }
                case 'diagnose': {
                  el.scrollIntoView({block: 'center', inline: 'center'});
                  const r = el.getBoundingClientRect();
                  const visible = shown(el);
                  const x = r.left + r.width / 2, y = r.top + r.height / 2;
                  const inViewport = visible && x >= 0 && y >= 0 && x < innerWidth && y < innerHeight;
                  const top = inViewport ? document.elementFromPoint(x, y) : null;
                  const covered = !!top && top !== el && !el.contains(top);
                  let layer = null;
                  for (let n = top; covered && n && n !== document.body; n = n.parentElement) {
                    const p = getComputedStyle(n).position;
                    if (p === 'fixed' || p === 'sticky') { layer = n; break; }
                  }
                  const target = brief(el);
                  const field = inputOf(el);
                  target.type = el.getAttribute('type') || '';
                  target.readonly = !!(field && field.readOnly) || el.getAttribute('aria-readonly') === 'true';
                  target.disabled = !!el.disabled || !!(field && field.disabled) || el.getAttribute('aria-disabled') === 'true';
                  target.nativeSelect = el.tagName === 'SELECT';
                  target.expanded = el.getAttribute('aria-expanded') || '';
                  target.haspopup = el.getAttribute('aria-haspopup') || '';
                  return {visible, inViewport, covered, target, cover: covered ? brief(top) : null, layer: brief(layer),
                    popups: popups(el, 2).slice(0, 3).map(brief)};
                }
              }
              return null;
            };
            """;

    /** 动作超时之后的检查（只读）：可见、在不在屏幕里、被谁盖住、只读 / 禁用 / 原生 select、还开着哪些弹出层。 */
    static final String DIAGNOSE = """
            // jobapply:diagnose
            const el = await page.locator(args.target).elementHandle({timeout: 3000});
            return await page.evaluate(DOM, {op: 'diagnose', el});
            """;

    /** 填写之前的只读检查：一次查完这些 ref 的标签、type、readonly、disabled、是不是原生 select、能不能键入。 */
    static final String INSPECT = """
            // jobapply:inspect
            const out = {};
            for (const target of args.targets) {
              try {
                const el = await page.locator(target).elementHandle({timeout: 1500});
                if (el) out[target] = await page.evaluate(DOM, {op: 'inspect', el});
              } catch (e) {
                // 找不到的字段照常交给 MCP，由它报错
              }
            }
            return out;
            """;

    /**
     * 选下拉里的一项。原生 select：按规范化文字找到选项原文就返回（由 MCP 的 browser_select_option 去选），找不到不选、
     * 返回全部选项。别的下拉：真实点击打开 → 等新出现的弹出层 → 一层一层按「完全相同 → 规范化后相同 → 唯一的包含」
     * 找值并点击（级联每层一个值；没找到先 scrollTop，动不了再用真实鼠标滚轮；能输入的下拉没找到时输入第一层的值过滤）
     * → 点完传入的各层后若右边还展开了下一列且尚未完成赋值，不按 Escape，返回 need_more_levels 和下一列选项原文；
     * 否则读回显示的值是否对得上，还开着就按一次 Escape。有歧义或没找到时不点，返回这一层能看到的选项原文。
     */
    static final String SELECT = """
            // jobapply:select
            const deadline = Date.now() + 15000;
            const pause = ms => new Promise(r => setTimeout(r, ms));
            const norm = s => String(s || '').normalize('NFKC').toLowerCase().replace(/\\s+/g, '');
            const pick = (texts, want) => {
              const w = norm(want);
              let hit = texts.map((t, i) => i).filter(i => texts[i] === want);
              if (hit.length === 0) hit = texts.map((t, i) => i).filter(i => norm(texts[i]) === w);
              if (hit.length === 0 && w) hit = texts.map((t, i) => i).filter(i => norm(texts[i]).includes(w));
              if (hit.length === 0 && w) hit = texts.map((t, i) => i).filter(i => norm(texts[i]) && w.includes(norm(texts[i])));
              if (hit.length === 1) return {index: hit[0]};
              return hit.length > 1 ? {ambiguous: hit.map(i => texts[i])} : {};
            };
            const trigger = await page.locator(args.target).elementHandle({timeout: 3000});
            const info = await page.evaluate(DOM, {op: 'trigger', el: trigger});
            if (info.native) {
              const labels = info.options.filter(o => !o.disabled).map(o => o.label);
              const matched = [];
              for (const want of args.values) {
                const p = pick(labels, want);
                if (p.index === undefined) {
                  return {status: p.ambiguous ? 'ambiguous' : 'not_found', kind: 'native', level: 0, name: info.name,
                    candidates: p.ambiguous || [], options: labels.slice(0, 50), more: labels.length > 50};
                }
                matched.push(labels[p.index]);
              }
              return {status: 'native', values: matched};
            }
            const pre = await page.evaluateHandle(DOM, {op: 'popups', el: trigger});
            let popup = null;
            const findPopup = async (ms) => {
              for (const end = Date.now() + ms; Date.now() < Math.min(end, deadline); await pause(100)) {
                const h = await page.evaluateHandle(DOM, {op: 'popup', el: trigger, pre});
                const found = h.asElement();
                if (found) return found;
              }
              return null;
            };
            if (info.expanded) {
              popup = (await page.evaluateHandle(DOM, {op: 'popup', el: trigger, pre: []})).asElement();
            }
            if (!popup) {
              await trigger.click({timeout: 3000});
              popup = await findPopup(info.editable ? 800 : 3000);
              if (!popup) popup = (await page.evaluateHandle(DOM, {op: 'popup', el: trigger, pre: []})).asElement();
            }
            const isOpen = () => page.evaluate(DOM, {op: 'open', el: trigger, popup});
            // 列表刚出现时可能还在淡入、选项还没渲染：每 100 毫秒看一次，最多约 1.2 秒，有选项就马上往下走
            const waitItems = async (level) => {
              let view = await page.evaluate(DOM, {op: 'level', popup, level});
              for (const end = Date.now() + 1200; view.texts.length === 0 && Date.now() < Math.min(end, deadline);) {
                await pause(100);
                view = await page.evaluate(DOM, {op: 'level', popup, level});
              }
              return view;
            };
            const searchBox = async () => (await page.evaluateHandle(DOM, {op: 'search', el: trigger, popup})).asElement();
            const typeFilter = async (text) => {
              const box = await searchBox();
              if (!box) return false;
              await box.fill('', {timeout: 2000});
              await box.focus();
              await page.keyboard.type(text, {delay: 40});
              await pause(300);
              // 输入后列表可能重画成新的容器：这里看手上的弹出层本身还在不在（不看 aria-expanded），不在就重新找
              if (!popup || !(await page.evaluate(DOM, {op: 'open', popup}))) popup = await findPopup(3000);
              if (!popup) return true;
              let last = null;
              for (let stable = 0, end = Date.now() + 3000; stable < 2 && Date.now() < Math.min(end, deadline); await pause(200)) {
                const now = JSON.stringify((await page.evaluate(DOM, {op: 'level', popup, level: 0})).texts);
                stable = now !== '[]' && now === last ? stable + 1 : 0;
                last = now;
              }
              return true;
            };
            let typed = false;
            if (args.search || (!popup && info.editable)) typed = await typeFilter(args.values[0]);
            if (!popup) {
              return {status: 'no_popup', name: info.name};
            }
            // 还开着就把焦点放回触发元素、按一次 Escape；返回的「关了没有」照实说
            const close = async () => {
              if (await isOpen()) {
                await trigger.focus().catch(() => {});
                await page.keyboard.press('Escape');
                for (const end = Date.now() + 600; Date.now() < end && (await isOpen());) await pause(100);
              }
              return !(await isOpen());
            };
            const chosen = [];
            const verify = async () => {
              const shown = norm(await page.evaluate(DOM, {op: 'shown', el: trigger}));
              return chosen.length > 0 && shown.includes(norm(chosen[chosen.length - 1]));
            };
            for (let level = 0; level < args.values.length; level++) {
              const want = args.values[level];
              let seen = [];
              let found = null;
              let foundText = '';
              let scrolled = false;
              if (await page.evaluate(DOM, {op: 'scroll', popup, level, top: true})) await pause(150);
              for (let round = 0; round < 80 && Date.now() < deadline; round++) {
                const view = round === 0 ? await waitItems(level) : await page.evaluate(DOM, {op: 'level', popup, level});
                for (const t of view.texts) if (!seen.includes(t)) seen.push(t);
                const p = pick(view.texts, want);
                if (p.ambiguous) {
                  const closed = await close();
                  return {status: 'ambiguous', kind: 'custom', level, name: info.name, levels: chosen.length, candidates: p.ambiguous,
                    options: seen.slice(0, 50), more: seen.length > 50, closed};
                }
                if (p.index !== undefined) {
                  foundText = view.texts[p.index];
                  found = (await page.evaluateHandle(DOM, {op: 'item', popup, level, index: p.index, text: foundText})).asElement();
                  if (found) break;
                  continue;
                }
                if (await page.evaluate(DOM, {op: 'scroll', popup, level})) {
                  scrolled = true;
                  await pause(150);
                  continue;
                }
                const rect = await page.evaluate(DOM, {op: 'colRect', popup, level});
                if (rect) {
                  const before = view.texts.slice();
                  await page.mouse.move(rect.x, rect.y);
                  await page.mouse.wheel(0, Math.max(160, rect.height * 0.7));
                  await pause(150);
                  const wheeled = await page.evaluate(DOM, {op: 'level', popup, level});
                  if (wheeled.texts.some(t => !before.includes(t))) {
                    scrolled = true;
                    continue;
                  }
                }
                if (level === 0 && !typed && (await searchBox())) {
                  typed = await typeFilter(want);
                  seen = [];
                  continue;
                }
                break;
              }
              if (!found) {
                const closed = await close();
                return {status: 'not_found', kind: 'custom', level, name: info.name, levels: chosen.length,
                  options: seen.slice(0, 50), more: seen.length > 50 || scrolled && seen.length >= 50, closed};
              }
              const before = await page.evaluate(DOM, {op: 'level', popup, level: level + 1});
              await found.click({timeout: 3000});
              chosen.push(foundText);
              if (level + 1 < args.values.length) {
                for (let end = Date.now() + 2500; Date.now() < Math.min(end, deadline); await pause(100)) {
                  const after = await page.evaluate(DOM, {op: 'level', popup, level: level + 1});
                  if (!(await isOpen())) break;
                  if (after.columns > level + 1 || JSON.stringify(after.texts) !== JSON.stringify(before.texts)) break;
                }
                if (!(await isOpen())) {
                  return {status: 'closed_early', kind: 'custom', level, name: info.name, levels: chosen.length,
                    verified: await verify()};
                }
              }
            }
            await pause(250);
            if (await isOpen()) {
              const after = await page.evaluate(DOM, {op: 'level', popup, level: args.values.length});
              if (after.columns > args.values.length && after.texts.length > 0 && !(await verify())) {
                return {status: 'need_more_levels', kind: 'custom', name: info.name, levels: chosen.length, chosen,
                  level: args.values.length, options: after.texts.slice(0, 50), more: after.texts.length > 50, closed: false};
              }
            }
            const closed = await close();
            return {status: 'selected', kind: 'custom', name: info.name, levels: chosen.length, closed, verified: await verify()};
            """;

    /**
     * 设一个日期（YYYY-MM-DD / YYYY-MM / YYYY）。原生 date / month 框直接设 ISO 值；能键入的框按占位符的格式键入再 Tab，
     * 读回对得上就结束；只读的框（或键入没生效）点开面板：面板里有输入框就在那里键入；头部有原生年/月 select 就直接切；
     * 否则点头部的年份 / 月份切到年份表、月份表跳过去（点了标题视图不变则改用左右箭头在一次调用里连续翻页），再点日期格。
     * 没有日历网格、只有年/月/日列的滚轮面板按列滚动后点击。只点面板里的格子、头部的年月和翻页箭头，不点确定、今天、清除；
     * 面板要按「确定」才生效时返回 needs_confirm（由模型自己去点）。找不到时不点，返回面板上看到的内容。
     * 要到日的控件只给了年月（C016 实跑后修正）：面板翻到那一年那一月、不点任何日期，按一次 Escape 关上面板（开着的面板
     * 会盖住日期框，之后设完整日期时点不开），返回 need_day；原生 date 框同样返回 need_day，不动它。模型拿它去问用户是哪一天。
     */
    static final String DATE = """
            // jobapply:date
            const deadline = Date.now() + 15000;
            const pause = ms => new Promise(r => setTimeout(r, ms));
            const m = /^(\\d{4})(?:-(\\d{1,2}))?(?:-(\\d{1,2}))?$/.exec(String(args.date).trim());
            if (!m) return {status: 'bad_date'};
            const y = +m[1], mo = m[2] ? +m[2] : null, d = m[3] ? +m[3] : null;
            const pad = n => String(n).padStart(2, '0');
            const digits = s => String(s || '').replace(/\\D+/g, ' ').trim().split(' ').filter(Boolean).map(Number);
            const matches = s => {
              const n = digits(s);
              const yi = n.indexOf(y);
              if (yi < 0) return false;
              const rest = n.filter((v, i) => i !== yi);
              return (mo === null || rest.includes(mo)) && (d === null || rest.filter(v => v !== mo || mo === d).includes(d));
            };
            const PANEL = (input) => {
              const shown = n => {
                if (!n || !n.isConnected || n.nodeType !== 1) return false;
                const b = n.getBoundingClientRect(), s = getComputedStyle(n);
                return b.width > 0 && b.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
              };
              const textOf = n => String(n.innerText || n.textContent || '').replace(/\\s+/g, ' ').trim();
              const STOP = /^(确定|确认|提交|完成|ok|confirm|submit|done|今天|此刻|现在|today|now|清除|清空|clear)$/i;
              const {op, popup} = input;
              const hit = n => {
                const r = n.getBoundingClientRect();
                const t = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
                return !!t && (t === n || n.contains(t) || t.contains(n));
              };
              const top = list => { const h = list.filter(hit); return h.length ? h : list; };
              const cells = top([...popup.querySelectorAll('td,[role=gridcell]')].filter(c => shown(c) && !c.closest('thead')
                && !c.querySelector('td,[role=gridcell]')));
              const header = top([...popup.querySelectorAll('*')].filter(n => shown(n) && !n.closest('td,[role=gridcell],thead,tfoot')
                && n.children.length === 0 && textOf(n)));
              const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
              const ZH = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '十一', '十二'];
              const monthOf = t => {
                let r = /^(\\d{1,2})\\s*月$/.exec(t) || /^0?(\\d{1,2})$/.exec(t);
                if (r && +r[1] >= 1 && +r[1] <= 12) return +r[1];
                r = /^([一二三四五六七八九十]{1,3})月$/.exec(t);
                if (r && ZH.indexOf(r[1]) >= 0) return ZH.indexOf(r[1]) + 1;
                const i = MONTHS.indexOf(t.slice(0, 3).toLowerCase());
                return i >= 0 && /^[a-z]{3,9}\\.?$/i.test(t) ? i + 1 : null;
              };
              const hasInView = cells.some(c => /in-view/.test(String(c.className)));
              const off = c => hasInView && !/in-view/.test(String(c.className)) || /(^|[\\s_-])(prev|next|last|old|new|other|outside|disabled)(-month|-year|-decade)?([\\s_-]|$)|is-disabled|not-in-view|out-of-range/i
                .test(String(c.className) + ' ' + String(c.parentElement && c.parentElement.className))
                && !/in-view/.test(String(c.className))
                || c.getAttribute('aria-disabled') === 'true';
              const texts = cells.map(textOf);
              const kind = (() => {
                if (texts.filter(t => /^\\d{4}\\s*[-–~]\\s*\\d{4}$/.test(t)).length >= 3) return 'decade';
                if (texts.filter(t => /^\\d{4}\\s*年?$/.test(t)).length >= 6) return 'year';
                if (texts.filter(t => monthOf(t) !== null && !/^\\d+$/.test(t)).length >= 12) return 'month';
                if (texts.filter(t => /^\\d{1,2}$/.test(t)).length >= 28) return 'day';
                if (cells.length <= 16 && texts.filter(t => monthOf(t) !== null).length >= 12) return 'month';
                return 'unknown';
              })();
              let hy = null, hm = null;
              for (const n of header) {
                const t = textOf(n);
                let r = /(\\d{4})\\s*年?\\s*(?:[-/.]?\\s*(\\d{1,2})\\s*月?)?$/.exec(t);
                if (r && hy === null && !/\\d{4}\\s*[-–~]\\s*\\d{4}/.test(t)) { hy = +r[1]; if (r[2]) hm = +r[2]; continue; }
                const mm = monthOf(t.replace(/\\s+/g, ''));
                if (mm !== null && hm === null && !/^\\d+$/.test(t)) hm = mm;
              }
              for (const s of [...popup.querySelectorAll('select')].filter(shown)) {
                const opt = (s.selectedOptions && s.selectedOptions[0]) || s.options[s.selectedIndex];
                const t = opt ? String(opt.label || opt.text || opt.value || '').trim() : '';
                if (hy === null && /^\\d{4}\\s*年?$/.test(t)) { hy = parseInt(t, 10); continue; }
                const mm = monthOf(t.replace(/\\s+/g, ''));
                if (hm === null && mm !== null) hm = mm;
              }
              if (op === 'view') return {kind, year: hy, month: hm, cells: texts.slice(0, 60),
                inView: cells.filter(c => !off(c)).map(textOf),
                header: header.map(textOf).slice(0, 12),
                confirm: [...popup.querySelectorAll('button,a,[role=button],span')].some(b => shown(b)
                  && /^(确定|确认|完成|ok|confirm|done)$/i.test(textOf(b)))};
              const clean = n => n && !STOP.test(textOf(n)) ? n : null;
              if (op === 'cell') {
                const want = input.want;
                const i = cells.findIndex(c => !off(c) && (input.kind === 'month' ? monthOf(textOf(c)) === want
                  : input.kind === 'decade' ? (() => { const r = /^(\\d{4})\\D+(\\d{4})$/.exec(textOf(c)); return r && +r[1] <= want && want <= +r[2]; })()
                  : input.kind === 'year' ? parseInt(textOf(c), 10) === want : textOf(c) === String(want)));
                return i >= 0 ? clean(cells[i]) : null;
              }
              if (op === 'label') {
                const n = header.find(h => input.part === 'year' ? /\\d{4}/.test(textOf(h)) && !/\\d{4}\\s*[-–~]\\s*\\d{4}/.test(textOf(h))
                  : monthOf(textOf(h).replace(/\\s+/g, '')) !== null && !/^\\d{4}/.test(textOf(h)));
                return clean(n && (n.closest('button,a,[role=button]') || n));
              }
              if (op === 'arrow') {
                const all = top([...popup.querySelectorAll('button,a,[role=button],i,span')].filter(n => shown(n)
                  && !n.closest('td,[role=gridcell]') && !STOP.test(textOf(n))));
                const desc = n => (String(n.className) + ' ' + (n.getAttribute('aria-label') || '') + ' ' + (n.getAttribute('title') || '')
                  + ' ' + textOf(n)).toLowerCase();
                const dir = input.dir < 0 ? /prev|上一|前一|left|«|‹/ : /next|下一|后一|right|»|›/;
                const big = /year|super|decade|century|d-arrow|double|«|»|年/;
                const found = all.filter(n => dir.test(desc(n)) && (input.big ? big.test(desc(n)) : !big.test(desc(n))));
                let n = found[0];
                if (!n) {
                  const box = popup.getBoundingClientRect();
                  const band = all.filter(el => {
                    const r = el.getBoundingClientRect();
                    return r.top + r.height / 2 <= box.top + Math.max(64, box.height * 0.35);
                  });
                  const side = band.filter(el => {
                    const r = el.getBoundingClientRect();
                    return input.dir < 0 ? r.left < box.left + box.width * 0.35 : r.right > box.left + box.width * 0.65;
                  });
                  n = side.sort((a, b) => a.getBoundingClientRect().width - b.getBoundingClientRect().width)[0];
                }
                return n ? (n.closest('button,a,[role=button]') || n) : null;
              }
              if (op === 'headerSelect') {
                const want = input.want, part = input.part;
                for (const s of [...popup.querySelectorAll('select')].filter(shown)) {
                  const hit = [...s.options].some(o => {
                    const t = String(o.label || o.text || o.value || '').replace(/\\s+/g, '');
                    if (part === 'year') return parseInt(t, 10) === want || parseInt(o.value, 10) === want;
                    return monthOf(t) === want || monthOf(String(o.value || '').replace(/\\s+/g, '')) === want;
                  });
                  if (hit) return s;
                }
                return null;
              }
              if (op === 'input') {
                return [...popup.querySelectorAll('input')].find(i => shown(i) && !i.readOnly && !i.disabled) || null;
              }
              return null;
            };
            const target = await page.locator(args.target).elementHandle({timeout: 3000});
            const field = (await target.evaluateHandle(el => el.matches('input,textarea') ? el : el.querySelector('input'))).asElement();
            const readBack = async () => field ? field.evaluate(i => i.value) : target.evaluate(el => el.innerText || '');
            const fieldInfo = field ? await field.evaluate(i => ({type: (i.type || '').toLowerCase(), readonly: i.readOnly,
              disabled: i.disabled, placeholder: i.getAttribute('placeholder') || '', value: i.value})) : null;
            if (fieldInfo && fieldInfo.disabled) return {status: 'disabled'};
            if (fieldInfo && ['date', 'month', 'datetime-local', 'week'].includes(fieldInfo.type)) {
              if (fieldInfo.type !== 'month' && d === null) return {status: 'need_day'};
              if (mo === null) return {status: 'need_month'};
              const iso = fieldInfo.type === 'month' ? y + '-' + pad(mo) : y + '-' + pad(mo) + '-' + pad(d)
                + (fieldInfo.type === 'datetime-local' ? 'T00:00' : '');
              await field.fill(iso, {timeout: 3000});
              return {status: 'set', how: 'native', verified: matches(await readBack())};
            }
            const format = (sample) => {
              const s = String(sample || '');
              const r = /(yyyy|YYYY)(.*?)(mm|MM)(?:(.*?)(dd|DD))?(.*)$/.exec(s);
              if (r) {
                let out = y + r[2];
                if (mo === null) return null;
                out += pad(mo);
                if (r[5]) { if (d === null) return null; out += r[4] + pad(d); }
                return out + (r[6] && !/[a-z]/i.test(r[6]) ? r[6] : '');
              }
              const v = /^(\\d{4})(\\D+)(\\d{1,2})(?:(\\D+)(\\d{1,2}))?(\\D*)$/.exec(s);
              if (v && mo !== null && (!v[5] || d !== null)) return y + v[2] + pad(mo) + (v[5] ? v[4] + pad(d) : '') + v[6];
              return null;
            };
            const pre = await page.evaluateHandle(DOM, {op: 'popups', el: target});
            const findPanel = async (ms) => {
              for (const end = Date.now() + ms; Date.now() < Math.min(end, deadline); await pause(100)) {
                const h = (await page.evaluateHandle(DOM, {op: 'popup', el: target, pre})).asElement();
                if (h) return h;
              }
              return null;
            };
            // 只给了年月、框却要到日（占位符或现有值带日）：不键入半个日期，去面板翻到那个月再返回 need_day
            const dayMissing = mo !== null && d === null && !!fieldInfo
              && (/dd/i.test(fieldInfo.placeholder) || /^\\d{4}\\D+\\d{1,2}\\D+\\d{1,2}/.test(fieldInfo.value));
            let typedTried = false;
            if (fieldInfo && !fieldInfo.readonly && !dayMissing) {
              const text = format(fieldInfo.placeholder) || format(fieldInfo.value)
                || (mo === null ? String(y) : y + '-' + pad(mo) + (d === null ? '' : '-' + pad(d)));
              await field.click({timeout: 3000});
              await field.fill('', {timeout: 2000});
              await page.keyboard.type(text, {delay: 20});
              await page.keyboard.press('Tab');
              await pause(300);
              if (matches(await readBack())) return {status: 'set', how: 'typed', verified: true};
              typedTried = true;
            }
            let panel = await findPanel(200);
            if (!panel) {
              await (field || target).click({timeout: 3000});
              panel = await findPanel(3000);
            }
            if (!panel) panel = (await page.evaluateHandle(DOM, {op: 'popup', el: target, pre: []})).asElement();
            if (!panel) return dayMissing ? {status: 'need_day', how: 'none'} : {status: 'no_panel', typedTried};
            const panelOpen = () => page.evaluate(DOM, {op: 'open', el: field || target, popup: panel});
            const monthOfText = t => {
              const s = String(t || '').replace(/\\s+/g, '');
              let r = /^(\\d{1,2})月$/.exec(s);
              if (r && +r[1] >= 1 && +r[1] <= 12) return +r[1];
              r = /^([一二三四五六七八九十]{1,3})月$/.exec(s);
              const ZH = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '十一', '十二'];
              if (r && ZH.indexOf(r[1]) >= 0) return ZH.indexOf(r[1]) + 1;
              const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
              const i = MONTHS.indexOf(s.slice(0, 3).toLowerCase());
              return i >= 0 && /^[a-z]{3,9}\\.?$/i.test(s) ? i + 1 : null;
            };
            const classifyDateCol = texts => {
              const list = (texts || []).map(t => String(t).trim());
              const yearHits = list.filter(t => /^\\d{4}年?$/.test(t)).length;
              const monthHits = list.filter(t => {
                const s = t.replace(/\\s+/g, '');
                return /月$/.test(s) || (monthOfText(s) !== null && !/^\\d+$/.test(s));
              }).length;
              const dayHits = list.filter(t => { const r = /^(\\d{1,2})日?$/.exec(t); return r && +r[1] >= 1 && +r[1] <= 31; }).length;
              if (yearHits >= 1 && yearHits >= monthHits && yearHits >= dayHits) return 'year';
              if (monthHits >= 1 && monthHits >= dayHits) return 'month';
              if (dayHits >= 1) return 'day';
              return null;
            };
            const loadColumns = async () => {
              const cols = await page.evaluate(DOM, {op: 'columnsInfo', popup: panel}) || [];
              return cols.slice(0, 3).map((c, i) => Object.assign({}, c, {part: classifyDateCol(c.texts), index: i}));
            };
            const overlayColumns = async (v) => {
              if (v && v.kind !== 'unknown' && v.kind !== 'columns') return v;
              const cols = await loadColumns();
              if (cols.length < 1 || cols.length > 3) return v;
              if (!cols.some(c => c.part === 'year' || c.part === 'month' || c.part === 'day')) return v;
              return Object.assign({}, v, {kind: 'columns', cells: cols.flatMap(c => c.texts).slice(0, 60)});
            };
            // 面板刚出现时可能还在淡入、格子还没渲染：每 100 毫秒看一次，最多约 1.2 秒，认出是哪种表就马上往下走
            const viewNow = async () => {
              let v = await page.evaluate(PANEL, {op: 'view', popup: panel});
              for (const end = Date.now() + 1200; v.kind === 'unknown' && Date.now() < Math.min(end, deadline);) {
                await pause(100);
                v = await page.evaluate(PANEL, {op: 'view', popup: panel});
              }
              return overlayColumns(v);
            };
            // need_day 时把面板关上（按一次 Escape）：开着的面板常盖住日期框，问完用户再设完整日期时点不开它
            const closePanel = async () => {
              if (await panelOpen()) {
                const focus = (await page.evaluateHandle(PANEL, {op: 'input', popup: panel})).asElement() || field || target;
                await focus.focus().catch(() => {});
                await page.keyboard.press('Escape');
                for (const end = Date.now() + 600; Date.now() < end && (await panelOpen());) await pause(100);
              }
              return !(await panelOpen());
            };
            let startKind = null;
            const inner = (await page.evaluateHandle(PANEL, {op: 'input', popup: panel})).asElement();
            if (inner && mo !== null) {
              const ph = await inner.evaluate(i => i.getAttribute('placeholder') || i.value || '');
              const text = format(ph);
              if (text) {
                await inner.fill('', {timeout: 2000});
                await inner.focus();
                await page.keyboard.type(text, {delay: 20});
                await pause(300);
              }
            }
            const click = async (h) => { await h.click({timeout: 3000}); await pause(200); };
            const get = async (o) => (await page.evaluateHandle(PANEL, {popup: panel, ...o})).asElement();
            const selectHeader = async (part, want) => {
              if (want === null) return false;
              const h = await get({op: 'headerSelect', part, want});
              if (!h) return false;
              const picked = await h.evaluate((s, {want, part}) => {
                const monthOf = t => {
                  const x = String(t || '').replace(/\\s+/g, '');
                  let r = /^(\\d{1,2})月$/.exec(x);
                  if (r && +r[1] >= 1 && +r[1] <= 12) return +r[1];
                  const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
                  const i = MONTHS.indexOf(x.slice(0, 3).toLowerCase());
                  return i >= 0 ? i + 1 : null;
                };
                const opt = [...s.options].find(o => {
                  const t = o.label || o.text || o.value || '';
                  if (part === 'year') return parseInt(t, 10) === want || parseInt(o.value, 10) === want;
                  return monthOf(t) === want || monthOf(o.value) === want || parseInt(t, 10) === want;
                });
                return opt && s.value !== opt.value ? opt.value : null;
              }, {want, part});
              if (picked === null || picked === undefined) return false;
              await h.selectOption(picked);
              await pause(200);
              return true;
            };
            const pickColumn = async (part, wantNum) => {
              if (wantNum === null || wantNum === undefined) return true;
              let dir = null;
              for (let round = 0; round < 40 && Date.now() < deadline; round++) {
                const cols = await loadColumns();
                const col = cols.find(c => c.part === part) || cols[{year: 0, month: 1, day: 2}[part]];
                if (!col) return false;
                const hit = col.texts.find(t => {
                  if (part === 'year') return parseInt(t, 10) === wantNum;
                  if (part === 'month') return monthOfText(String(t).replace(/\\s+/g, '')) === wantNum || parseInt(t, 10) === wantNum;
                  const r = /^(\\d{1,2})日?$/.exec(String(t).trim());
                  return r && +r[1] === wantNum;
                });
                if (hit) {
                  const index = col.texts.indexOf(hit);
                  await page.evaluate(DOM, {op: 'scrollItem', popup: panel, level: col.index, index, text: hit});
                  const h = (await page.evaluateHandle(DOM, {op: 'item', popup: panel, level: col.index, index, text: hit})).asElement();
                  if (h) {
                    await h.click({timeout: 3000});
                    await pause(150);
                    return true;
                  }
                }
                const nums = col.texts.map(t => part === 'month'
                  ? (monthOfText(String(t).replace(/\\s+/g, '')) || parseInt(t, 10))
                  : parseInt(t, 10)).filter(n => Number.isFinite(n) && n > 0);
                const cur = nums.length ? nums[Math.floor(nums.length / 2)] : null;
                if (dir === null) dir = (cur !== null && wantNum < cur) ? -1 : 1;
                await page.mouse.move(col.x, col.y);
                await page.mouse.wheel(0, dir * Math.max(160, col.height * 0.7));
                await pause(150);
                const after = await loadColumns();
                const next = after.find(c => c.part === part) || after[col.index];
                const grew = next && next.texts.some(t => !col.texts.includes(t));
                if (!grew && round === 2) dir = -dir;
              }
              return false;
            };
            let last = null;
            let labelDead = false;
            for (let step = 0; step < 40 && Date.now() < deadline; step++) {
              if (!(await panelOpen())) {
                const value = await readBack();
                return {status: matches(value) ? 'set' : 'closed', how: startKind === 'columns' ? 'wheel' : 'panel',
                  verified: matches(value)};
              }
              const view = await viewNow();
              last = view;
              if (startKind === null) startKind = view.kind;
              let h = null;
              if (view.kind !== 'columns' && mo !== null && (view.year !== y || view.month !== mo)) {
                const beforeYm = {year: view.year, month: view.month, kind: view.kind};
                const yChanged = view.year !== y && await selectHeader('year', y);
                const mChanged = view.month !== mo && await selectHeader('month', mo);
                if (yChanged || mChanged) {
                  const afterYm = await viewNow();
                  if (afterYm.year !== beforeYm.year || afterYm.month !== beforeYm.month || afterYm.kind !== beforeYm.kind) {
                    continue;
                  }
                }
              }
              if (view.kind === 'columns') {
                await pickColumn('year', y);
                if (mo !== null) await pickColumn('month', mo);
                const cols = await loadColumns();
                const hasDay = cols.some(c => c.part === 'day');
                if (d === null && hasDay) return {status: 'need_day', how: 'wheel', closed: await closePanel()};
                if (d !== null && hasDay) await pickColumn('day', d);
                const after = await page.evaluate(PANEL, {op: 'view', popup: panel});
                const value = await readBack();
                return {status: after.confirm ? 'needs_confirm' : matches(value) ? 'set' : (await panelOpen() ? 'open' : 'closed'),
                  how: 'wheel', verified: matches(value)};
              }
              if (view.kind === 'day') {
                if (mo === null) {
                  h = await get({op: 'label', part: view.year === y ? 'month' : 'year'});
                } else if (view.year === y && view.month === mo) {
                  // 要到日的面板只给了年月：已经翻到那个月，不点任何日期，关上面板，交回模型去问用户是哪一天
                  if (d === null) return {status: 'need_day', how: 'panel', closed: await closePanel()};
                  h = await get({op: 'cell', kind: 'day', want: d});
                  if (!h) break;
                  await click(h);
                  if (!(await panelOpen())) continue;
                  const after = await page.evaluate(PANEL, {op: 'view', popup: panel});
                  const value = await readBack();
                  return {status: after.confirm ? 'needs_confirm' : matches(value) ? 'set' : 'open', how: 'panel',
                    verified: matches(value)};
                } else {
                  if (!labelDead) {
                    const before = {kind: view.kind, year: view.year, month: view.month};
                    h = await get({op: 'label', part: view.year === y ? 'month' : 'year'});
                    if (h) {
                      await click(h);
                      const after = await viewNow();
                      if (after.kind === before.kind && after.year === before.year && after.month === before.month) {
                        labelDead = true;
                      } else {
                        continue;
                      }
                    }
                  }
                  const dist = (view.year !== null && view.month !== null && mo !== null)
                    ? (y - view.year) * 12 + (mo - view.month) : null;
                  if (dist) {
                    const n = Math.min(180, Math.abs(dist));
                    const dir = dist < 0 ? -1 : 1;
                    let moved = false;
                    for (let i = 0; i < n && Date.now() < deadline; i++) {
                      const ah = await get({op: 'arrow', dir, big: false});
                      if (!ah) break;
                      await ah.click({timeout: 3000});
                      await pause(40);
                      moved = true;
                      const now = await page.evaluate(PANEL, {op: 'view', popup: panel});
                      if (now.year === y && now.month === mo) break;
                    }
                    if (moved) continue;
                  }
                  h = await get({op: 'arrow', dir: view.year === y ? mo - view.month : y - view.year,
                    big: view.year !== y});
                }
              } else if (view.kind === 'month') {
                if (view.year === y || view.year === null) {
                  if (mo === null) break;
                  h = await get({op: 'cell', kind: 'month', want: mo});
                  if (!h) break;
                  await click(h);
                  // 面板一开始是日期表（要到日的控件），是系统自己切到月份表的：点了月份会回到日期表，接着走
                  if (d === null && startKind !== 'day') {
                    const open = await panelOpen();
                    const after = open ? await page.evaluate(PANEL, {op: 'view', popup: panel}) : null;
                    const value = await readBack();
                    return {status: after && after.confirm ? 'needs_confirm' : matches(value) ? 'set' : open ? 'open' : 'closed',
                      how: 'panel', verified: matches(value)};
                  }
                  continue;
                } else if (Math.abs(y - view.year) <= 3) {
                  h = await get({op: 'arrow', dir: y - view.year, big: true});
                } else {
                  h = await get({op: 'label', part: 'year'});
                  if (!h) h = await get({op: 'arrow', dir: y - view.year, big: true});
                }
              } else if (view.kind === 'year') {
                h = await get({op: 'cell', kind: 'year', want: y});
                if (h) {
                  await click(h);
                  if (mo === null) {
                    const value = await readBack();
                    return {status: matches(value) ? 'set' : 'open', how: 'panel', verified: matches(value)};
                  }
                  continue;
                }
                const years = view.inView.map(t => parseInt(t, 10)).filter(n => n > 999);
                h = await get({op: 'arrow', dir: years.length && y < Math.min(...years) ? -1 : 1, big: true});
              } else if (view.kind === 'decade') {
                h = await get({op: 'cell', kind: 'decade', want: y});
                if (!h) {
                  const ys = view.inView.map(t => parseInt(t, 10)).filter(n => n > 999);
                  h = await get({op: 'arrow', dir: ys.length && y < Math.min(...ys) ? -1 : 1, big: true});
                }
              }
              if (!h) break;
              await click(h);
            }
            return {status: 'not_found', how: last && last.kind === 'columns' ? 'wheel' : 'panel', typedTried,
              panel: last && {kind: last.kind, header: last.header, cells: (last.cells || []).slice(0, 40)}};
            """;

    private PageScripts() {
    }

    /** 一段可以交给 {@code browser_run_code_unsafe} 的代码：参数只以 JSON 嵌进开头那一行。 */
    static String code(String steps, Map<String, Object> args) {
        return "async (page) => {\nconst args = " + JSON.writeValueAsString(args) + ";\n" + DOM + "\n" + steps + "\n}";
    }
}
