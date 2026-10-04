/* 家庭主页：成员列表 + 设备列表
 *
 * 权限相关（后端已判，前端只做展示控制，不重复造规则）：
 *   - 成员增删：仅群主（is_owner=true 才显示按钮）
 *   - 设备绑定：家庭成员均可（绑到自己名下）
 */

import { familyApi, deviceApi } from '../api.js';
import { isNativeShell, saveDeviceUid, startHeartbeat } from '../native.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import {
  skeleton, emptyState, promptBox, confirmBox, showError, toastOk,
  fmtRelative, errorState } from '../ui.js';
import * as store from '../store.js';
import { escapeHtml } from '../router.js';

let familyUid = null;

export async function render(params) {
  familyUid = params.uid;
  setLayout({ title: '家庭', backBtn: true, tabbar: false });
  setView(`<div class="page">${skeleton(4)}</div>`);

  let fam, devices = [], invitations = [];
  try {
    fam = await familyApi.detail(familyUid);
    store.setCurrentFamily(fam);
    // 设备列表按家庭查；失败不阻塞成员展示
    try {
      devices = (await deviceApi.list(familyUid)) || [];
    } catch { devices = []; }
    // 待处理邀请（仅群主可见）；失败不影响主流程
    try {
      invitations = (await familyApi.listInvitations(familyUid)) || [];
    } catch { invitations = []; }
  } catch (e) {
    showError(e);
    setView(`<div class="page">${errorState(e, () => render(params), { backHref: '#/families' })}</div>`);
    return;
  }
  scrollTop();

  const me = store.getUser();
  const isOwner = fam.is_owner;
  renderPage(fam, devices, isOwner, me, invitations);
  return () => { /* 无轮询，无需清理 */ };
}

/**
 * 某成员名下的设备数。
 * 用 owner_uid 关联（不是昵称）—— 昵称可重名、可改名，无法可靠匹配。
 * 无权查看的设备 owner_uid 为 null，自然不计入，与脱敏口径一致。
 */
function devCountOf(member) {
  if (!member || !member.uid) return 0;
  return (devicesCache || []).filter((d) => d.owner_uid === member.uid).length;
}

let devicesCache = [];

function renderPage(fam, devices, isOwner, me, invitations = []) {
  devicesCache = devices || [];
  setLayout({
    title: fam.name,
    backBtn: true,
    tabbar: false,
    actions: isOwner ? [{ text: '加成员', onClick: () => addMember(fam) }] : [],
  });

  // ---- 成员 ----
  const members = (fam.members || []).map((m) => {
    const self = me && m.uid === me.uid;
    const canRemove = isOwner && !m.is_owner;   // 群主不能移出自己
    return `
      <div class="item">
        <!-- 点主体区域进入成员详情；「移出」按钮单独在外，避免误触发 -->
        <a class="member-hit" href="#/family/${encodeURIComponent(fam.uid)}/member/${encodeURIComponent(m.uid)}">
          <div class="thumb">${m.is_owner ? '👑' : '👤'}</div>
          <div class="grow">
            <div class="t">${escapeHtml(m.nickname)}
              ${self ? '<span class="tag">我</span>' : ''}
              ${m.is_owner ? '<span class="tag info">群主</span>' : ''}
            </div>
            <div class="s">${escapeHtml(m.phone_masked)} · 加入于 ${fmtRelative(m.joined_at)}</div>
            <div class="s">
              ${devCountOf(m)
                ? `<span class="tag">${devCountOf(m)} 台设备</span>`
                : '<span class="muted">暂无设备</span>'}
            </div>
          </div>
          <span class="arrow">›</span>
        </a>
        ${canRemove
          ? `<button class="btn danger-ghost sm" data-remove="${escapeHtml(m.uid)}"
                     data-name="${escapeHtml(m.nickname)}">移出</button>`
          : ''}
      </div>`;
  }).join('');

  // 待处理邀请（只有群主看得到）：单独一组，明确标注「待对方同意」
  const inviteHtml = invitations.length
    ? `
      <div class="card">
        <div class="card-hd">
          <span class="grow">待对方同意（${invitations.length}）</span>
        </div>
        <div class="card-bd tight">
          ${invitations.map((iv) => `
            <div class="item">
              <div class="thumb" style="background:#fffaf0;color:#b7791f">✉️</div>
              <div class="grow">
                <div class="t">${escapeHtml(iv.invitee_nickname)}
                  <span class="tag warn">等待同意</span>
                </div>
                <div class="s">邀请发送于 ${fmtRelative(iv.created_at)}</div>
              </div>
              <button class="btn ghost sm" data-cancel-inv="${escapeHtml(iv.invitation_uid)}">
                撤销
              </button>
            </div>`).join('')}
        </div>
      </div>` : '';

  setView(`
    <div class="page">
      <div class="card">
        <div class="card-hd">
          <span class="grow">成员（${fam.member_count}）</span>
          ${isOwner ? '<button class="btn ghost sm" id="btn-add">加成员</button>' : ''}
        </div>
        <div class="card-bd tight">${members}</div>
      </div>

      ${inviteHtml}

      <!-- 设备不再单列：设备归属到人，点成员即可看他的设备（含多台） -->
      <div class="card">
        <div class="card-bd tight">
          <button class="item as-row" id="btn-bind">
            <div class="thumb">📱</div>
            <div class="grow">
              <div class="t">绑定新设备</div>
              <div class="s">本家庭共 ${devices.length} 台设备，点成员查看各自名下的设备</div>
            </div>
            <span class="arrow">＋</span>
          </button>
        </div>
      </div>

      ${isOwner ? '' : `
      <div class="card">
        <div class="card-bd">
          <button class="btn danger-ghost block" id="btn-quit">退出该家庭</button>
          <div class="hint">退出后将失去该家庭内所有设备的访问权限</div>
        </div>
      </div>`}

      <div class="card">
        <div class="card-bd tight">
          <a class="item" href="#/family/${encodeURIComponent(fam.uid)}/logs">
            <div class="thumb">📋</div>
            <div class="grow">
              <div class="t">操作记录</div>
              <div class="s">谁邀请过谁、谁加入或被移出、谁设过闹钟</div>
            </div>
            <span class="arrow">›</span>
          </a>
        </div>
      </div>

      ${isOwner ? `
      <div class="note">
        邀请成员后，需对方在「消息」中同意才会加入。
        未同意前会显示在「待对方同意」分组，可随时撤销。
      </div>` : ''}

      <div class="note">
        点成员可查看他名下的设备与权限 —— 设备归属是「个人」而非「家庭」，
        同一位成员可能有多台设备。
      </div>
    </div>
  `);

  // ---- 事件 ----
  const addBtn = document.getElementById('btn-add');
  if (addBtn) addBtn.addEventListener('click', () => addMember(fam));
  document.getElementById('btn-bind').addEventListener('click', () => bindDevice(fam));

  document.querySelectorAll('[data-remove]').forEach((b) => {
    b.addEventListener('click', (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      removeMember(fam, b.getAttribute('data-remove'), b.getAttribute('data-name'));
    });
  });

  document.querySelectorAll('[data-cancel-inv]').forEach((b) => {
    b.addEventListener('click', (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      cancelInvitation(fam, b.getAttribute('data-cancel-inv'));
    });
  });

  const quitBtn = document.getElementById('btn-quit');
  if (quitBtn) quitBtn.addEventListener('click', () => quitFamily(fam));
}

/** 邀请成员：发邀请，等对方同意（不再直接拉人进群） */
async function addMember(fam) {
  const phone = await promptBox('邀请成员', {
    label: '对方手机号',
    placeholder: '11 位手机号',
    inputType: 'tel',
    okText: '发送邀请',
  });
  if (!phone) return;
  if (!/^1[3-9]\d{9}$/.test(phone)) {
    showError({ message: '请输入正确的 11 位手机号' });
    return;
  }
  try {
    const r = await familyApi.inviteMember(fam.uid, phone);
    const name = (r && r.invitee_nickname) || '对方';
    toastOk(`邀请已发送给【${name}】，等待对方同意`);
    render({ uid: fam.uid });
  } catch (e) {
    // 后端已给出用户化文案（如「已向该成员发送过邀请，等待对方同意」）
    showError(e);
  }
}

/** 撤销尚未被接受的邀请 */
async function cancelInvitation(fam, invUid) {
  const ok = await confirmBox(
    '撤销邀请',
    '撤销后对方将不再看到该邀请。若之后仍需邀请，可重新发送。',
    { okText: '撤销', danger: true }
  );
  if (!ok) return;
  try {
    await familyApi.cancelInvitation(fam.uid, invUid);
    toastOk('已撤销邀请');
    render({ uid: fam.uid });
  } catch (e) {
    showError(e);
  }
}

async function removeMember(fam, memberUid, memberName) {
  const yes = await confirmBox(
    '移出成员',
    `确定把「${memberName}」移出家庭？\n他将失去该家庭所有设备的访问权限。`,
    { okText: '移出', danger: true }
  );
  if (!yes) return;
  try {
    await familyApi.removeMember(fam.uid, memberUid);
    toastOk('已移出');
    render({ uid: fam.uid });
  } catch (e) {
    showError(e);
  }
}

async function quitFamily(fam) {
  const yes = await confirmBox(
    '退出家庭',
    `确定退出「${fam.name}」？\n退出后需群主重新邀请才能回来。`,
    { okText: '退出', danger: true }
  );
  if (!yes) return;
  try {
    await familyApi.quit(fam.uid);
    toastOk('已退出');
    go('/families', { replace: true });
  } catch (e) {
    showError(e);
  }
}

/** 绑设备：名称 + 平台（后端 platform 只接受 android / ios） */
async function bindDevice(fam) {
  const frag = document.createDocumentFragment();

  const f1 = document.createElement('div');
  f1.className = 'field';
  f1.innerHTML = '<label>设备名称</label>';
  const nameInput = document.createElement('input');
  nameInput.type = 'text';
  nameInput.maxLength = 20;
  nameInput.placeholder = '如：爸的手机';
  f1.appendChild(nameInput);

  const f2 = document.createElement('div');
  f2.className = 'field';
  f2.innerHTML = '<label>设备平台</label>';
  const platSel = document.createElement('select');
  platSel.innerHTML = `
    <option value="android">Android</option>
    <option value="ios">iPhone / iPad</option>`;
  f2.appendChild(platSel);

  const tip = document.createElement('div');
  tip.className = 'note';
  tip.textContent = '绑定后该设备归你所有，你可以设置信任成员与查看授权。';

  frag.append(f1, f2, tip);

  const { openModal, closeModal } = await import('../ui.js');
  openModal({
    title: '绑定设备',
    body: frag,
    center: true,
    actions: [
      { text: '取消', kind: 'ghost' },
      {
        text: '绑定',
        onClick: async () => {
          const nm = nameInput.value.trim();
          if (!nm) { showError({ message: '请填写设备名称' }); return false; }
          try {
            const d = await deviceApi.bind(fam.uid, nm, platSel.value);
            closeModal();
            toastOk('绑定成功');

            // 原生壳：把设备 uid 交给原生并启动心跳。
            // 心跳是「设备在线」的依据（后端 120 秒无心跳判离线），
            // 也是开机重排时定位本机闹钟列表所必需。
            if (isNativeShell) {
              saveDeviceUid(d.uid, nm);
              startHeartbeat();
            }

            go(`/device/${encodeURIComponent(d.uid)}`);
          } catch (e) {
            showError(e);
            return false;
          }
        },
      },
    ],
  });
  setTimeout(() => nameInput.focus(), 120);
}
