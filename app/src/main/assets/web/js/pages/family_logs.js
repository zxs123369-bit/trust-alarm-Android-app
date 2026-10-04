/* 家庭操作日志页（按人视角看"谁在这个家庭里做了什么"）
 *
 * 与 device logs 的区别：
 *   设备日志（/device/:uid/logs）→ 某台设备上的操作（谁设了闹钟等）
 *   家庭日志（/family/:uid/logs）→ 整个家庭范围的操作（谁加入/被移出/邀请等）
 *
 * 后端契约（GET /logs/operations?family_uid=）：
 *   {list: [{id, action, operator_uid, operator_nickname, device_id,
 *            family_id, target_id, detail, created_at}], total}
 *   ★ 注意：家庭日志里 device_id 可能为 null（如家庭级动作），
 *     所以设备名要容错处理。
 *
 * 权限：家庭日志的细粒度记录（§6.2 家庭操作记录）是二期付费功能，
 *      这里展示的是 §6.1 普通操作日志的家庭维度，成员即可查看。
 */
import { logApi, familyApi } from '../api.js';
import { setLayout, setView, scrollTop } from '../layout.js';
import { skeleton, emptyState, showError, fmtRelative, fmtTime, errorState } from '../ui.js';
import { escapeHtml } from '../router.js';

let familyUid = null;
let familyName = '家庭';
let rows = [];
let total = 0;
/** 'time' 按天 | 'person' 按人 */
let viewMode = 'person';

export async function render(params) {
  familyUid = params.uid;
  viewMode = 'person';   // 家庭日志默认按人看（更有信息量）
  setLayout({ title: '家庭日志', backBtn: true, tabbar: false });
  setView(`<div class="page">${skeleton(4)}</div>`);

  try {
    const f = await familyApi.detail(familyUid);
    familyName = f.name || familyName;
  } catch { /* 用默认名 */ }

  try {
    const r = await logApi.operations({ family_uid: familyUid, limit: 100 });
    rows = (r && r.list) || [];
    total = (r && r.total) || rows.length;
  } catch (e) {
    showError(e);
    setView(`<div class="page">${errorState(e, () => render(params), { backHref: '#/families' })}</div>`);
    return;
  }
  scrollTop();
  draw();
  return () => { /* 无轮询 */ };
}

function draw() {
  setLayout({
    title: '家庭日志',
    backBtn: true,
    tabbar: false,
    actions: [{ text: '刷新', onClick: () => render({ uid: familyUid }) }],
  });

  if (!rows.length) {
    setView(`
      <div class="page">
        ${emptyState('📋', '暂无家庭操作记录',
          '成员加入、被移出、邀请等操作会记录在这里')}
      </div>`);
    return;
  }

  const html = viewMode === 'person' ? renderByPerson(rows) : renderByDay(rows);

  setView(`
    <div class="page">
      <div class="seg mb8">
        <button class="seg-btn ${viewMode === 'person' ? 'on' : ''}" data-vm="person">按人</button>
        <button class="seg-btn ${viewMode === 'time' ? 'on' : ''}" data-vm="time">按时间</button>
      </div>

      <div class="row between mb8">
        <div class="muted small">共 ${total} 条</div>
        <div class="muted small">${escapeHtml(familyName)}</div>
      </div>

      ${html}

      <div class="note mt12">
        「按人」视图把同一个人在这个家庭里的所有操作聚合在一起，
        方便回答「谁邀请过谁、谁被移出过」。
        更细的家庭操作记录为二期付费功能。
      </div>
    </div>
  `);

  document.querySelectorAll('[data-vm]').forEach((b) => {
    b.addEventListener('click', () => {
      viewMode = b.getAttribute('data-vm');
      draw();
    });
  });
}

function groupByPerson(list) {
  const map = new Map();
  list.forEach((it) => {
    const key = it.operator_uid || '(unknown)';
    if (!map.has(key)) {
      map.set(key, {
        uid: key,
        nickname: it.operator_nickname || '未知用户',
        count: 0,
        items: [],
      });
    }
    const g = map.get(key);
    g.count += 1;
    g.items.push(it);
  });
  return Array.from(map.values()).sort((a, b) => b.count - a.count);
}

function groupByDay(list) {
  const map = new Map();
  list.forEach((it) => {
    const d = new Date(Number(it.created_at));
    const key = `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
    if (!map.has(key)) map.set(key, { label: dayLabel(d), items: [] });
    map.get(key).items.push(it);
  });
  return Array.from(map.values());
}

function dayLabel(d) {
  const today = new Date();
  const same = (a, b) => a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  if (same(d, today)) return '今天';
  const y = new Date(today);
  y.setDate(y.getDate() - 1);
  if (same(d, y)) return '昨天';
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function renderByDay(list) {
  return groupByDay(list).map((g) => `
    <div class="muted small" style="margin:14px 2px 6px">${escapeHtml(g.label)}</div>
    <div class="card"><div class="card-bd tight">
      ${g.items.map((it) => row(it, {})).join('')}
    </div></div>
  `).join('');
}

function renderByPerson(list) {
  return groupByPerson(list).map((p) => {
    const kinds = {};
    p.items.forEach((it) => {
      const m = ACTION_META[it.action] || { text: it.action || '操作' };
      kinds[m.text] = (kinds[m.text] || 0) + 1;
    });
    const kindText = Object.entries(kinds)
      .sort((a, b) => b[1] - a[1])
      .map(([k, v]) => `${k} ${v}`)
      .join(' · ');

    return `
      <div class="card">
        <div class="card-hd">
          <div class="thumb" style="margin-right:10px">👤</div>
          <div class="grow">
            <div class="t">${escapeHtml(p.nickname)}
              <span class="tag">${p.count} 次</span>
            </div>
            <div class="s">${escapeHtml(kindText)}</div>
          </div>
        </div>
        <div class="card-bd tight">
          ${p.items.map((it) => row(it, { showWho: false })).join('')}
        </div>
      </div>`;
  }).join('');
}

function row(it, opts = {}) {
  const meta = ACTION_META[it.action] || { icon: '•', text: it.action || '操作' };
  const who = it.operator_nickname || '未知用户';

  // 优先后端 summary（如「儿子 向【爸的手机】设置了 08:30 的闹钟」）。
  // 「按人」视图里操作人在分组标题上，故去掉开头的「谁 」避免重复。
  let text = it.summary || '';
  if (text && opts.showWho === false) {
    text = text.replace(new RegExp('^' + who.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*'), '');
  }

  return `
    <div class="item" style="align-items:flex-start">
      <div class="thumb" style="width:32px;height:32px;font-size:15px">${meta.icon}</div>
      <div class="grow">
        <div class="t" style="font-size:14.5px">
          ${text
            ? escapeHtml(text)
            : `${opts.showWho === false ? '' : `<b>${escapeHtml(who)}</b>`}<span class="muted"> ${escapeHtml(meta.text)}</span>`}
        </div>
        <div class="s muted3">${fmtTime(it.created_at)} · ${fmtRelative(it.created_at)}</div>
      </div>
    </div>`;
}

/* 动作类型 → 图标 + 中文（含家庭级动作，与设备日志的动作集合有交集） */
const ACTION_META = {
  family_create:  { icon: '🏠', text: '创建了家庭' },
  family_join:    { icon: '✅', text: '加入家庭（接受邀请）' },
  family_leave:   { icon: '🚪', text: '退出了家庭' },
  family_kick:    { icon: '👋', text: '移出了成员' },
  family_invite:  { icon: '✉️', text: '发送了加入邀请' },
  alarm_create:   { icon: '➕', text: '创建了闹钟' },
  alarm_update:   { icon: '✏️', text: '修改了闹钟' },
  alarm_delete:   { icon: '🗑️', text: '删除了闹钟' },
  dispatch:       { icon: '📤', text: '下发闹钟' },
  confirm:        { icon: '👍', text: '确认了闹钟' },
  switch_change:  { icon: '🔀', text: '变更信任开关' },
  trust_add:      { icon: '🤝', text: '添加信任成员' },
  trust_remove:   { icon: '💔', text: '移除信任成员' },
  rate_limited:   { icon: '⏳', text: '被限流拦截' },
};
