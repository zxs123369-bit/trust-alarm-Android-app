/* 查看申请页（P4）
 *
 * 后端真实契约（实测）：
 *   GET  /view-requests/received?status=   → {list:[...], total}
 *   GET  /view-requests/mine               → {list:[...], total}
 *   POST /view-requests/{id}/approve
 *   POST /view-requests/{id}/reject
 *
 * 单条字段：id(int!) device_uid device_name applicant_uid applicant_nickname
 *          applicant_phone_masked owner_uid status handled_at created_at
 * status: pending / approved / rejected
 *
 * 注意 id 是**数字**，不是 uid。
 */

import { grantApi } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import { escapeHtml } from '../router.js';
import {
  skeleton, emptyState, confirmBox, showError, toastOk, fmtRelative,
} from '../ui.js';

let tab = 'received';       // received | mine
let data = { received: null, mine: null };

export async function render() {
  setLayout({ title: '查看申请', backBtn: true, tabbar: false });
  setView(`<div class="page">${skeleton(3)}</div>`);
  await load();
  scrollTop();
  draw();
  return () => { /* 无轮询 */ };
}

async function load() {
  try {
    const [recv, mine] = await Promise.all([
      grantApi.received(),
      grantApi.mine(),
    ]);
    data.received = norm(recv);
    data.mine = norm(mine);
  } catch (e) {
    showError(e);
    data.received = data.received || [];
    data.mine = data.mine || [];
  }
}

/** 统一取 list（后端返回 {list,total}；容错裸数组） */
function norm(r) {
  if (Array.isArray(r)) return r;
  return (r && r.list) || [];
}

function draw() {
  const recvList = data.received || [];
  const mineList = data.mine || [];
  const recvPending = recvList.filter((x) => x.status === 'pending').length;

  setLayout({
    title: '查看申请',
    backBtn: true,
    tabbar: false,
  });

  const list = tab === 'received' ? recvList : mineList;

  let body;
  if (!list.length) {
    body = tab === 'received'
      ? emptyState('📥', '没有收到申请', '他人申请查看你的设备时会出现在这里')
      : emptyState('📤', '你还没发起过申请', '在设备详情页可申请查看他人设备');
  } else {
    body = list.map((it) => card(it)).join('');
  }

  setView(`
    <div class="page">
      <div class="card">
        <div class="card-bd tight">
          <div class="row" style="padding:4px 0">
            <button class="btn ${tab === 'received' ? '' : 'ghost'} sm grow" id="tab-recv">
              我收到的${recvPending ? ` (${recvPending} 待处理)` : ''}
            </button>
            <button class="btn ${tab === 'mine' ? '' : 'ghost'} sm grow" id="tab-mine">
              我发起的 (${mineList.length})
            </button>
          </div>
        </div>
      </div>

      ${body}

      <div class="note mt12">
        同意后对方可<b>只读查看</b>该设备的闹钟列表，但<b>不能</b>为你设置闹钟。
        <br>若同意后发现不妥，可到「查看授权」页撤销。
      </div>
    </div>
  `);

  document.getElementById('tab-recv').addEventListener('click', () => {
    tab = 'received';
    draw();
  });
  document.getElementById('tab-mine').addEventListener('click', () => {
    tab = 'mine';
    draw();
  });

  bindActions();
}

function card(it) {
  const st = statusMeta(it.status);
  const isReceived = tab === 'received';
  const who = isReceived
    ? `${escapeHtml(it.applicant_nickname || '未知')}（${escapeHtml(it.applicant_phone_masked || '')}）`
    : `设备主人`;

  return `
    <div class="card">
      <div class="card-bd">
        <div class="row between" style="align-items:flex-start">
          <div class="grow">
            <div style="font-weight:600;font-size:14.5px">
              ${isReceived ? '申请查看' : '我申请查看'}
              「${escapeHtml(it.device_name || '未知设备')}」
            </div>
            <div class="small muted mt8">
              ${isReceived ? `申请人：${who}` : ''}
              ${!isReceived ? `设备归属人处理中` : ''}
            </div>
            <div class="small muted3 mt8">${fmtRelative(it.created_at)}</div>
          </div>
          <span class="tag ${st.cls}">${st.text}</span>
        </div>

        ${isReceived && it.status === 'pending' ? `
          <div class="btn-row mt12">
            <button class="btn sm" data-approve="${it.id}">同意</button>
            <button class="btn ghost sm" data-reject="${it.id}">拒绝</button>
          </div>` : ''}

        ${isReceived && it.status === 'approved' ? `
          <div class="btn-row mt12">
            <button class="btn ghost sm" data-goto-grants="${escapeHtml(it.device_uid)}">
              管理授权
            </button>
          </div>` : ''}
      </div>
    </div>`;
}

function statusMeta(s) {
  return {
    pending: { text: '待处理', cls: 'warn' },
    approved: { text: '已同意', cls: 'ok' },
    rejected: { text: '已拒绝', cls: 'err' },
  }[s] || { text: s || '未知', cls: '' };
}

function bindActions() {
  document.querySelectorAll('[data-approve]').forEach((b) => {
    b.addEventListener('click', (ev) => {
      ev.stopPropagation();
      handle(Number(b.getAttribute('data-approve')), true);
    });
  });
  document.querySelectorAll('[data-reject]').forEach((b) => {
    b.addEventListener('click', (ev) => {
      ev.stopPropagation();
      handle(Number(b.getAttribute('data-reject')), false);
    });
  });
  document.querySelectorAll('[data-goto-grants]').forEach((b) => {
    b.addEventListener('click', (ev) => {
      ev.stopPropagation();
      go(`/device/${encodeURIComponent(b.getAttribute('data-goto-grants'))}/grants`);
    });
  });
}

async function handle(id, approve) {
  const ok = await confirmBox(
    approve ? '同意查看申请' : '拒绝查看申请',
    approve
      ? '同意后对方可只读查看该设备的闹钟列表（不能设置闹钟）。'
      : '拒绝后对方无法查看该设备。',
    { okText: approve ? '同意' : '拒绝', danger: !approve }
  );
  if (!ok) return;
  try {
    if (approve) await grantApi.approve(id);
    else await grantApi.reject(id);
    toastOk(approve ? '已同意' : '已拒绝');
    await load();
    draw();
  } catch (e) {
    showError(e);
  }
}
