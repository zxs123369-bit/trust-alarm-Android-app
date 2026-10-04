/* 信任名单页（P4）
 *
 * PRD §4.2：信任 ≠ 查看授权，两套体系完全独立。
 *   - 信任成员：可免确认下发闹钟（需配合「信任自动下发开关」）+ 可查看闹钟
 *   - 查看授权：只读查看（在「查看授权」页管理）
 *
 * 后端真实契约（实测）：
 *   GET    /devices/{uid}/trust-members   → 裸数组 [{uid,nickname,phone_masked,created_at}]
 *   POST   /devices/{uid}/trust-members   {phone}
 *   DELETE /devices/{uid}/trust-members/{member_uid}
 *   PATCH  /devices/{uid}/trust-switch    {enabled}
 *   GET    /devices/{uid}                 → auto_alarm_trust_switch: 0|1
 *
 * 错误语义（后端已定，前端直接展示）：
 *   重复添加 409/1002「该成员已在信任名单中」
 *   未注册   404/1001「该手机号尚未注册」
 */

import { deviceApi, trustApi } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import {
  skeleton, emptyState, promptBox, confirmBox, showError, toastOk, fmtRelative, errorState } from '../ui.js';
import { escapeHtml } from '../router.js';

let deviceUid = null;
let device = null;
let members = [];
let switchOn = false;

export async function render(params) {
  deviceUid = params.uid;
  setLayout({ title: '信任名单', backBtn: true, tabbar: false });
  setView(`<div class="page">${skeleton(3)}</div>`);

  try {
    device = await deviceApi.detail(deviceUid);
    switchOn = device.auto_alarm_trust_switch === 1;
    const r = await trustApi.list(deviceUid);
    // 注意：这里是裸数组，不是 {list:[...]}
    members = Array.isArray(r) ? r : (r && r.list) || [];
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
    title: '信任名单',
    backBtn: true,
    tabbar: false,
    actions: isOwner ? [{ text: '添加', onClick: addMember }] : [],
  });

  if (!isOwner) {
    // 非归属人：只读展示，说明为什么不能改
    setView(`
      <div class="page">
        ${emptyState('🔒', '仅设备归属人可管理',
                     '你不是「' + devName + '」的归属人，无法修改信任名单')}
        ${members.length ? `
        <div class="card mt12">
          <div class="card-hd">信任成员（${members.length}）</div>
          <div class="card-bd tight">${members.map(memberRow).join('')}</div>
        </div>` : ''}
      </div>
    `);
    return;
  }

  const listHtml = members.length
    ? members.map(memberRow).join('')
    : emptyState('🤝', '还没有信任成员', '添加家人后，他们可免确认为你设置闹钟');

  setView(`
    <div class="page">
      <div class="card">
        <div class="card-hd">
          <span class="grow">信任自动下发</span>
          <span class="tag ${switchOn ? 'ok' : ''}">${switchOn ? '已开启' : '已关闭'}</span>
        </div>
        <div class="card-bd">
          <div class="row between">
            <div class="small muted" style="flex:1;padding-right:12px">
              开启后，信任成员可免确认直接为「${escapeHtml(devName)}」设置闹钟
            </div>
            <button class="btn ${switchOn ? 'ghost' : ''} sm" id="btn-switch">
              ${switchOn ? '关闭' : '开启'}
            </button>
          </div>
          <div class="note ${switchOn ? 'ok' : 'warn'} mt12">
            ${switchOn
              ? '当前：信任成员下发将免确认（iPhone 仍需在 App 内确认一次）'
              : '当前：所有人下发的闹钟都需要你确认'}
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-hd">
          <span class="grow">信任成员（${members.length}）</span>
          <button class="btn ghost sm" id="btn-add">添加</button>
        </div>
        <div class="card-bd tight">${listHtml}</div>
      </div>

      <div class="note">
        信任成员可查看该设备的闹钟；开启上方开关后还可免确认下发。
        <br>「信任」与「查看授权」是两套独立权限，互不影响。
      </div>
    </div>
  `);

  document.getElementById('btn-switch').addEventListener('click', toggleSwitch);
  document.getElementById('btn-add').addEventListener('click', addMember);
  bindRemove();
}

function memberRow(m) {
  return `
    <div class="item">
      <div class="thumb">👤</div>
      <div class="grow">
        <div class="t">${escapeHtml(m.nickname || '未知')}</div>
        <div class="s">${escapeHtml(m.phone_masked || '')} · 加入于 ${fmtRelative(m.created_at)}</div>
      </div>
      <button class="btn danger-ghost sm" data-rm="${escapeHtml(m.uid)}"
              data-name="${escapeHtml(m.nickname || '')}">移除</button>
    </div>`;
}

function bindRemove() {
  document.querySelectorAll('[data-rm]').forEach((b) => {
    b.addEventListener('click', async (ev) => {
      ev.stopPropagation();
      await removeMember(b.getAttribute('data-rm'), b.getAttribute('data-name'));
    });
  });
}

async function toggleSwitch() {
  const next = !switchOn;
  if (next) {
    const ok = await confirmBox(
      '开启信任自动下发',
      '开启后，信任成员为你设置的闹钟将不再需要你确认，会直接生效。\n\n' +
      'iPhone 因系统限制，仍需在手机 App 内确认一次。',
      { okText: '开启' }
    );
    if (!ok) return;
  }
  try {
    const r = await trustApi.setSwitch(deviceUid, next);
    switchOn = r && typeof r.auto_alarm_trust_switch === 'number'
      ? r.auto_alarm_trust_switch === 1
      : next;
    toastOk(switchOn ? '已开启信任自动下发' : '已关闭');
    draw();
  } catch (e) {
    showError(e);
  }
}

async function addMember() {
  const phone = await promptBox('添加信任成员', {
    label: '对方手机号',
    placeholder: '需是同一家庭的成员',
    inputType: 'tel',
    okText: '添加',
  });
  if (!phone) return;
  if (!/^1[3-9]\d{9}$/.test(phone)) {
    showError({ message: '请输入正确的 11 位手机号' });
    return;
  }
  try {
    await trustApi.add(deviceUid, phone);
    toastOk('已添加');
    const r = await trustApi.list(deviceUid);
    members = Array.isArray(r) ? r : (r && r.list) || [];
    draw();
  } catch (e) {
    // 后端文案已是用户化表述（如「该成员已在信任名单中」），直接展示
    showError(e);
  }
}

async function removeMember(uid, name) {
  const ok = await confirmBox(
    '移除信任成员',
    `确定把「${name}」移出信任名单？\n移除后他将失去查看权限，也无法免确认下发。`,
    { okText: '移除', danger: true }
  );
  if (!ok) return;
  try {
    await trustApi.remove(deviceUid, uid);
    toastOk('已移除');
    members = members.filter((m) => m.uid !== uid);
    draw();
  } catch (e) {
    showError(e);
  }
}
