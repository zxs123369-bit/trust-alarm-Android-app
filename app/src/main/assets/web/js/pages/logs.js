/* 操作日志页（P5）
 *
 * 后端真实契约（实测）：
 *   GET /logs/operations?device_uid=<必需>&limit=<可选>
 *     无 device_uid → 400/1000
 *     未知设备      → 404/1001
 *     成功          → {list:[...], total}，单次上限 100 条
 *
 * 单条字段：id(int) action operator_uid operator_nickname
 *          device_id(int) family_id(int|null) target_id(int) detail created_at
 *
 * 动作类型（实测 8 种）：见 ACTION_META
 *
 * 注意：detail 字段目前后端恒为 null，所以不做展示。
 * target_id 是内部数字 id（不是 uid），不对用户展示 —— PRD §13 要求
 * 界面上不出现技术标识，故只呈现「谁 + 做了什么 + 什么时候」。
 */

import { logApi, deviceApi } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import { skeleton, emptyState, showError, fmtRelative, fmtTime, errorState } from '../ui.js';
import { escapeHtml } from '../router.js';

const PAGE_SIZE = 50;

let deviceUid = null;
let deviceName = '设备';
let rows = [];
let total = 0;
let loading = false;
/** 视图模式：'time' 按天时间线 | 'person' 按操作人聚合 */
let viewMode = 'time';

export async function render(params) {
  deviceUid = (params && params.uid) || '';
  setLayout({ title: '操作日志', backBtn: true, tabbar: false });

  // ★ 缺 uid 时给出可操作的引导，而不是「加载失败：请指定设备」(真机实测)。
  //   该路由正常都带 uid 进入；用户回退或点过期链接会丢掉它。
  //   原行为把「缺参数」当成「加载失败」展示，红色警告框让用户以为出错了。
  if (!deviceUid) {
    setView(`
      <div class="page">
        ${emptyState('📄', '请选择要查看的设备',
          '操作日志按设备记录。请从设备列表进入。')}
        <a class="btn block mt12" href="#/devices">去设备列表</a>
        <a class="btn ghost block mt12" href="#/local-alarms">回到本地闹钟</a>
      </div>`);
    return () => { /* 无轮询 */ };
  }

  setView(`<div class="page">${skeleton(4)}</div>`);

  try {
    const d = await deviceApi.detail(deviceUid);
    deviceName = d.device_name || deviceName;
  } catch { /* 取不到就用默认名 */ }

  try {
    const r = await logApi.operations({ device_uid: deviceUid, limit: PAGE_SIZE });
    rows = (r && r.list) || [];
    total = (r && r.total) || rows.length;
  } catch (e) {
    showError(e);
    setView(`<div class="page">${errorState(e, () => render(params), { backHref: '#/devices' })}</div>`);
    return;
  }
  scrollTop();
  draw();
  return () => { /* 无轮询 */ };
}

function draw() {
  const isOwner = true;   // 能进到这一步说明有权查看（后端已判）

  setLayout({
    title: '操作日志',
    backBtn: true,
    tabbar: false,
    actions: [{ text: '刷新', onClick: () => render({ uid: deviceUid }) }],
  });

  if (!rows.length) {
    setView(`
      <div class="page">
        ${emptyState('📋', '暂无操作记录',
          '设置闹钟、开关信任等操作会记录在这里')}
      </div>
    `);
    return;
  }

  // 两种视角：按时间（默认）看事件流；按人看「谁在这台设备上做了什么」
  const html = viewMode === 'person'
    ? renderByPerson(rows)
    : renderByDay(rows);

  // 操作人概览：让"谁动过我的设备"一眼可见
  const actors = groupByPerson(rows);
  const actorBar = actors.length > 1
    ? `<div class="chips">
         ${actors.map((a) => `
           <span class="chip">${escapeHtml(a.nickname)} · ${a.count}</span>`).join('')}
       </div>`
    : '';

  setView(`
    <div class="page">
      <div class="seg mb8">
        <button class="seg-btn ${viewMode === 'time' ? 'on' : ''}" data-vm="time">按时间</button>
        <button class="seg-btn ${viewMode === 'person' ? 'on' : ''}" data-vm="person">按人</button>
      </div>

      <div class="row between mb8">
        <div class="muted small">共 ${total} 条${rows.length < total ? `（显示最近 ${rows.length} 条）` : ''}</div>
        <div class="muted small">${escapeHtml(deviceName)}</div>
      </div>

      ${actorBar}
      ${html}

      <div class="note mt12">
        ${viewMode === 'person'
          ? '"按人"视图把同一个人在这台设备上的所有操作聚合在一起，方便回答「谁动过我的设备」。'
          : '日志记录设备的操作历史。切成「按人」可按操作者查看。'}
        家庭操作记录与设备访问记录为二期付费功能。
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

/** 按天分组（时间线视角） */
function renderByDay(list) {
  const groups = groupByDay(list);
  return groups.map((g) => `
    <div class="muted small" style="margin:14px 2px 6px">${escapeHtml(g.label)}</div>
    <div class="card">
      <div class="card-bd tight">
        ${g.items.map(row).join('')}
      </div>
    </div>
  `).join('');
}

/** 按操作人分组（成员视角） */
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

function renderByPerson(list) {
  const people = groupByPerson(list);
  return people.map((p) => {
    // 该人做过的动作种类统计（一眼看出"他只是设闹钟"还是"还改了信任"）
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
              <span class="tag">${p.count} 次操作</span>
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

function groupByDay(list) {
  const map = new Map();
  list.forEach((it) => {
    const d = new Date(Number(it.created_at));
    const key = `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
    if (!map.has(key)) {
      map.set(key, { label: dayLabel(d), items: [] });
    }
    map.get(key).items.push(it);
  });
  return Array.from(map.values());
}

function dayLabel(d) {
  const today = new Date();
  const sameDay = (a, b) =>
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate();
  if (sameDay(d, today)) return '今天';
  const y = new Date(today);
  y.setDate(y.getDate() - 1);
  if (sameDay(d, y)) return '昨天';
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/**
 * 单条日志。
 * @param it 日志对象
 * @param opts.showWho 「按人」视图里操作人已在分组标题上，无需重复显示
 */
function row(it, opts = {}) {
  const { showWho = true } = opts;
  const meta = ACTION_META[it.action] || { icon: '•', text: it.action || '操作', cls: '' };
  const who = it.operator_nickname || '未知用户';

  // 后端给了一句话摘要（谁 + 向谁/哪台设备 + 做了什么 + 什么时间），
  // 优先用它 —— 比前端拼接更准确，也保证各端展示一致。
  // 「按人」视图里操作人已在分组标题上，故去掉开头的「谁 」。
  let text = it.summary || '';
  if (text && !showWho) {
    text = text.replace(new RegExp('^' + who.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*'), '');
  }

  return `
    <div class="item" style="align-items:flex-start">
      <div class="thumb" style="width:32px;height:32px;font-size:15px">${meta.icon}</div>
      <div class="grow">
        <div class="t" style="font-size:14.5px">
          ${text
            ? escapeHtml(text)
            : `${showWho ? `<b>${escapeHtml(who)}</b>` : ''}<span class="muted"> ${escapeHtml(meta.text)}</span>`}
        </div>
        <div class="s muted3">${fmtTime(it.created_at)} · ${fmtRelative(it.created_at)}</div>
      </div>
    </div>`;
}

/* 动作类型 → 图标 + 中文描述
 * 覆盖实测到的 8 种；未知类型回退为显示原始 action，不崩 */
const ACTION_META = {
  alarm_create:  { icon: '➕', text: '创建了闹钟' },
  alarm_update:  { icon: '✏️', text: '修改了闹钟' },
  alarm_delete:  { icon: '🗑️', text: '删除了闹钟' },
  dispatch:      { icon: '📤', text: '下发闹钟到设备' },
  confirm:       { icon: '✅', text: '确认了闹钟' },
  reject:        { icon: '🚫', text: '拒绝了闹钟' },
  switch_change: { icon: '🔀', text: '变更了信任自动下发开关' },
  trust_add:     { icon: '🤝', text: '添加了信任成员' },
  trust_remove:  { icon: '💔', text: '移除了信任成员' },
  view_request:  { icon: '✋', text: '发起了查看申请' },
  view_approve:  { icon: '👁️', text: '同意了查看申请' },
  view_revoke:   { icon: '⛔', text: '撤销了查看授权' },
  bind:          { icon: '📱', text: '绑定了设备' },
  unbind:        { icon: '🔌', text: '解绑了设备' },
  rename:        { icon: '🏷️', text: '重命名了设备' },
};
