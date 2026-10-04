/* 消息页（P5 范围，但确认闹钟的入口在这里，故与 P3 一起做）

后端真实结构（实测，非文档推测）：
  list[] = { uid, type, title, content, payload, is_read, created_at }
  payload 是 **JSON 字符串**，需二次解析拿 alarm_uid / device_uid
  顶层返回 { list, total }，没有 unread 计数 → 未读数前端按 is_read 统计

实测到的 type：'alarm_confirm'（他人请求设置闹钟，可直接同意/拒绝）
其他可能值按同结构展示，不做硬编码假设。
*/

import { messageApi, alarmApi, invitationApi } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import {
  skeleton, emptyState, showError, toastOk, confirmBox, fmtRelative, errorState } from '../ui.js';
import * as store from '../store.js';
import { escapeHtml } from '../router.js';

let data = null;

export async function render() {
  setLayout({ title: '消息', tabbar: true, activeTab: '/messages' });
  setView(`<div class="page">${skeleton(4)}</div>`);

  try {
    data = await messageApi.list();
  } catch (e) {
    showError(e);
    setView(`<div class="page">${errorState(e, () => render())}</div>`);
    return;
  }
  scrollTop();
  syncUnread();
  // 先取闹钟状态再绘制：决定是否显示「同意/拒绝」（与消息已读状态无关）
  const list0 = (data && data.list) || [];
  try { alarmStates = await loadAlarmStates(list0); } catch { alarmStates = null; }
  draw();
  return () => { /* 无轮询 */ };
}

function syncUnread() {
  const list = (data && data.list) || [];
  // 后端不返回 unread 计数，按 is_read 自行统计
  store.setUnread(list.filter((m) => !m.is_read).length);
}

/* 闹钟处理状态缓存：alarm_uid → confirm_status
 *
 * 为什么需要它：消息的 is_read 表示「用户看过」，与「闹钟是否已处理」是两件事。
 * 若用 is_read 控制按钮显隐，用户点一下消息（标为已读）按钮就消失了，
 * 导致该请求永远无法处理。所以必须按闹钟的真实状态判断。
 */
let alarmStates = null;      // { alarmUid: confirm_status }

async function loadAlarmStates(list) {
  const uids = [];
  list.forEach((m) => {
    const pl = parsePayload(m);
    if (pl.alarm_uid) uids.push({ uid: pl.alarm_uid, device: pl.device_uid });
  });
  if (!uids.length) return {};

  // 按设备分组，一次拉该设备全部闹钟，避免 N 次请求
  const byDevice = {};
  uids.forEach(({ uid, device }) => {
    if (device) (byDevice[device] = byDevice[device] || []).push(uid);
  });

  const { alarmApi } = await import('../api.js');
  const out = {};
  await Promise.all(Object.keys(byDevice).map(async (dev) => {
    try {
      const d = await alarmApi.list(dev);
      (d.list || []).forEach((a) => { out[a.uid] = a.confirm_status; });
    } catch { /* 无权查看该设备时跳过，退化为「显示按钮」 */ }
  }));
  return out;
}

/** 该消息对应的闹钟是否已处理（已处理则不再显示同意/拒绝）
 *  拿不到状态时返回 false（宁可多给一次按钮，也不要让用户无从操作） */
function isResolved(m) {
  const pl = parsePayload(m);
  // 邀请消息：已读即视为已处理（点过同意/拒绝后会重新拉取，按钮自然消失）
  if (pl.invitation_uid) return false;     // 状态由服务端接口保证唯一性
  if (!pl.alarm_uid) return true;          // 无关联闹钟 → 无动作可做
  if (!alarmStates) return false;
  const st = alarmStates[pl.alarm_uid];
  if (st === undefined) return false;      // 状态未知 → 保守显示
  return st === 1;                         // 1=已确认（确认或拒绝都会置位）
}

/** payload 是 JSON 字符串，解析失败时返回空对象（不让页面崩） */
function parsePayload(m) {
  if (!m || !m.payload) return {};
  try {
    return typeof m.payload === 'string' ? JSON.parse(m.payload) : m.payload;
  } catch {
    return {};
  }
}

function draw() {
  const list = (data && data.list) || [];
  const unread = list.filter((m) => !m.is_read).length;

  setLayout({
    title: '消息',
    tabbar: true,
    activeTab: '/messages',
    actions: unread ? [{
      text: '全部已读',
      onClick: async () => {
        try {
          await messageApi.readAll();
          toastOk('已全部标为已读');
          render();
        } catch (e) { showError(e); }
      },
    }] : [],
  });

  if (!list.length) {
    setView(`<div class="page">${emptyState('🔔', '暂无消息',
      '他人为你设置闹钟、申请查看设备时，会在这里通知你')}</div>`);
    return;
  }

  const html = list.map((m) => card(m)).join('');
  setView(`
    <div class="page">
      <div class="muted small mb8">共 ${list.length} 条${unread ? `，${unread} 条未读` : ''}</div>
      ${html}
    </div>
  `);
  bind(list);
}

function card(m) {
  const type = m.type || 'other';
  const unread = !m.is_read;
  const meta = typeMeta(type);
  const pl = parsePayload(m);
  const alarmUid = pl.alarm_uid || null;
  const invUid = pl.invitation_uid || null;

  return `
    <div class="card" data-msg="${escapeHtml(m.uid)}">
      <div class="card-bd">
        <div class="row" style="gap:10px;align-items:flex-start">
          <div class="thumb" style="width:34px;height:34px;font-size:17px">${meta.icon}</div>
          <div class="grow">
            <div class="row between" style="align-items:flex-start">
              <div style="font-weight:600;font-size:14.5px">
                ${escapeHtml(m.title || meta.title)}
                ${unread ? '<span class="tag err">未读</span>' : ''}
              </div>
              <div class="muted3" style="font-size:11.5px;white-space:nowrap">
                ${fmtRelative(m.created_at)}
              </div>
            </div>
            <div class="small muted mt8" style="white-space:pre-wrap">${escapeHtml(m.content || '')}</div>

            ${meta.action === 'alarm_confirm' && alarmUid && !isResolved(m) ? `
              <div class="btn-row mt12">
                <button class="btn sm" data-approve="${escapeHtml(alarmUid)}">同意</button>
                <button class="btn ghost sm" data-reject="${escapeHtml(alarmUid)}">拒绝</button>
              </div>` : ''}

            ${meta.action === 'invite_act' && invUid && !isResolved(m) ? `
              <div class="btn-row mt12">
                <button class="btn sm" data-inv-accept="${escapeHtml(invUid)}">同意加入</button>
                <button class="btn ghost sm" data-inv-reject="${escapeHtml(invUid)}">拒绝</button>
              </div>` : ''}

            ${meta.action === 'go_requests' ? `
              <button class="btn ghost sm mt12" data-goreq="1">去处理</button>` : ''}
          </div>
        </div>
      </div>
    </div>`;
}

/** 类型 → 图标/默认标题/是否有内联动作（真实 type 值以实测为准） */
function typeMeta(t) {
  return {
    alarm_confirm: { icon: '⏰', title: '闹钟确认', action: 'alarm_confirm' },
    alarm_result: { icon: '✅', title: '闹钟结果' },
    alarm_removed: { icon: '🗑️', title: '闹钟已删除' },
    view_request: { icon: '✋', title: '查看申请', action: 'go_requests' },
    view_approved: { icon: '👁️', title: '查看申请已同意' },
    view_rejected: { icon: '🚫', title: '查看申请被拒绝' },
    view_revoked: { icon: '⛔', title: '查看权限已撤销' },
    // 家庭邀请（邀请制：需对方同意才进群）
    family_invite: { icon: '✉️', title: '家庭邀请', action: 'invite_act' },
    family_joined: { icon: '🎉', title: '新成员加入' },
    family_invite_rejected: { icon: '🙅', title: '邀请被拒绝' },
    // 家庭成员变更（用户反馈「移出没有通知」后补上）
    family_kicked: { icon: '👋', title: '已被移出家庭' },
    family_quit: { icon: '🚪', title: '成员退出家庭' },
    family_dismissed: { icon: '🏚️', title: '家庭已解散' },
    ops_dead_letter: { icon: '⚠️', title: '运维告警' },
    trust_changed: { icon: '🤝', title: '信任变更' },
  }[t] || { icon: '🔔', title: '通知' };
}

function bind(list) {
  const markRead = async (uid) => {
    try {
      await messageApi.read(uid);
      const m = list.find((x) => x.uid === uid);
      if (m) m.is_read = 1;
      syncUnread();
    } catch { /* 静默：不影响主流程 */ }
  };

  // 点击卡片正文（非按钮）标记已读
  document.querySelectorAll('[data-msg]').forEach((el) => {
    el.addEventListener('click', (ev) => {
      if (ev.target.closest('button')) return;
      markRead(el.getAttribute('data-msg'));
    });
  });

  document.querySelectorAll('[data-approve]').forEach((b) => {
    b.addEventListener('click', (ev) => {
      ev.stopPropagation();
      act(b.getAttribute('data-approve'), true, b.closest('[data-msg]').getAttribute('data-msg'));
    });
  });
  document.querySelectorAll('[data-reject]').forEach((b) => {
    b.addEventListener('click', (ev) => {
      ev.stopPropagation();
      act(b.getAttribute('data-reject'), false, b.closest('[data-msg]').getAttribute('data-msg'));
    });
  });
  document.querySelectorAll('[data-goreq]').forEach((b) => {
    b.addEventListener('click', (ev) => {
      ev.stopPropagation();
      go('/requests');
    });
  });

  document.querySelectorAll('[data-inv-accept]').forEach((b) => {
    b.addEventListener('click', (ev) => {
      ev.stopPropagation();
      handleInvite(b.getAttribute('data-inv-accept'), true,
                   b.closest('[data-msg]').getAttribute('data-msg'));
    });
  });
  document.querySelectorAll('[data-inv-reject]').forEach((b) => {
    b.addEventListener('click', (ev) => {
      ev.stopPropagation();
      handleInvite(b.getAttribute('data-inv-reject'), false,
                   b.closest('[data-msg]').getAttribute('data-msg'));
    });
  });
}

/** 同意 / 拒绝家庭邀请（邀请制核心交互） */
async function handleInvite(invUid, accept, msgUid) {
  const yes = await confirmBox(
    accept ? '同意加入家庭' : '拒绝邀请',
    accept
      ? '同意后你将成为该家庭成员，可以看到家庭内的设备与闹钟。'
      : '拒绝后不会加入该家庭。',
    { okText: accept ? '同意加入' : '拒绝', danger: !accept }
  );
  if (!yes) return;
  try {
    if (accept) await invitationApi.accept(invUid);
    else await invitationApi.reject(invUid);
    toastOk(accept ? '已加入家庭' : '已拒绝');
    if (msgUid) {
      try { await messageApi.read(msgUid); } catch { /* ignore */ }
    }
    render();
  } catch (e) {
    showError(e);
  }
}

/** 在消息里直接同意/拒绝闹钟（PRD §4.1 闭环） */
async function act(alarmUid, approve, msgUid) {
  const yes = await confirmBox(
    approve ? '同意该闹钟' : '拒绝该闹钟',
    approve ? '同意后该闹钟将在你的设备上生效。' : '拒绝后该闹钟不会生效。',
    { okText: approve ? '同意' : '拒绝', danger: !approve }
  );
  if (!yes) return;
  try {
    await alarmApi.confirm(alarmUid, approve);
    toastOk(approve ? '已同意' : '已拒绝');
    // 顺手把这条消息标为已读
    if (msgUid) {
      try { await messageApi.read(msgUid); } catch { /* ignore */ }
    }
    render();
  } catch (e) {
    showError(e);
  }
}
