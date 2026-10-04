/* 查看授权页（P4）
 *
 * PRD §4.4：单独授权（≠ 信任），两套体系完全独立。
 * 授权是「只读查看」，不包含免确认下发。
 *
 * 后端真实契约（实测）：
 *   GET    /devices/{uid}/grants              → 裸数组 [{uid,nickname,phone_masked,created_at}]
 *   DELETE /devices/{uid}/grants/{member_uid}
 *   申请在「查看申请」页处理（/requests），本页只管理已生效的授权
 */

import { deviceApi, grantApi } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import {
  skeleton, emptyState, confirmBox, showError, toastOk, fmtRelative, errorState } from '../ui.js';
import { escapeHtml } from '../router.js';

let deviceUid = null;
let device = null;
let grants = [];

export async function render(params) {
  deviceUid = params.uid;
  setLayout({ title: '查看授权', backBtn: true, tabbar: false });
  setView(`<div class="page">${skeleton(3)}</div>`);

  try {
    device = await deviceApi.detail(deviceUid);
    const r = await grantApi.list(deviceUid);
    // 裸数组（与信任名单一致）
    grants = Array.isArray(r) ? r : (r && r.list) || [];
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
  const isOwner = !!(device && device.owner_is_me);
  const devName = (device && device.device_name) || '设备';

  setLayout({
    title: '查看授权',
    backBtn: true,
    tabbar: false,
    actions: isOwner ? [{ text: '处理申请', onClick: () => go('/requests') }] : [],
  });

  if (!isOwner) {
    setView(`
      <div class="page">
        ${emptyState('🔒', '仅设备归属人可管理',
                     '你不是「' + devName + '」的归属人')}
      </div>
    `);
    return;
  }

  const listHtml = grants.length
    ? grants.map(row).join('')
    : emptyState('👁️', '还没有单独授权', '当有人申请查看并被你同意后，会出现在这里');

  setView(`
    <div class="page">
      <div class="card">
        <div class="card-hd">
          <span class="grow">已授权（${grants.length}）</span>
          <button class="btn ghost sm" id="btn-req">看申请</button>
        </div>
        <div class="card-bd tight">${listHtml}</div>
      </div>

      <div class="note">
        被授权者可<b>只读查看</b>该设备的闹钟列表，但<b>不能</b>为你设置闹钟。
        <br>若要允许对方免确认设置闹钟，请到「信任名单」添加。
      </div>

      <div class="note warn mt12">
        撤销授权后，对方立即失去查看权限（含此前通过该授权看到的记录）。
      </div>
    </div>
  `);

  document.getElementById('btn-req').addEventListener('click', () => go('/requests'));
  document.querySelectorAll('[data-revoke]').forEach((b) => {
    b.addEventListener('click', async (ev) => {
      ev.stopPropagation();
      await revoke(b.getAttribute('data-revoke'), b.getAttribute('data-name'));
    });
  });
}

function row(g) {
  return `
    <div class="item">
      <div class="thumb">👁️</div>
      <div class="grow">
        <div class="t">${escapeHtml(g.nickname || '未知')}</div>
        <div class="s">${escapeHtml(g.phone_masked || '')} · 授权于 ${fmtRelative(g.created_at)}</div>
      </div>
      <button class="btn danger-ghost sm" data-revoke="${escapeHtml(g.uid)}"
              data-name="${escapeHtml(g.nickname || '')}">撤销</button>
    </div>`;
}

async function revoke(uid, name) {
  const ok = await confirmBox(
    '撤销查看授权',
    `确定撤销「${name}」的查看权限？\n撤销后对方立即无法查看该设备的闹钟。`,
    { okText: '撤销', danger: true }
  );
  if (!ok) return;
  try {
    await grantApi.revoke(deviceUid, uid);
    toastOk('已撤销');
    grants = grants.filter((g) => g.uid !== uid);
    draw();
  } catch (e) {
    showError(e);
  }
}
