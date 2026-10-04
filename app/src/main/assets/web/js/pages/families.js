/* 家庭列表页 —— 登录后的首页 */

import { familyApi, invitationApi } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import {
  skeleton, emptyState, promptBox, confirmBox, showError, toastOk,
  fmtTime, fmtRelative, errorState } from '../ui.js';
import * as store from '../store.js';
import { escapeHtml } from '../router.js';

export async function render() {
  setLayout({
    title: '家庭',
    tabbar: true,
    activeTab: '/families',
    actions: [{ text: '新建', onClick: createFamily }],
  });
  setView(`<div class="page">${skeleton(3)}</div>`);

  let families = [];
  let invitations = [];
  try {
    families = (await familyApi.list()) || [];
    // 待处理邀请：即使家庭列表为空也要显示，否则用户不知道有人邀请他
    try {
      const iv = await invitationApi.mine();
      invitations = (iv && iv.list) || [];
    } catch { invitations = []; }
  } catch (e) {
    showError(e);
    setView(`<div class="page">${errorState(e, () => render())}</div>`);
    return;
  }
  store.setFamilies(families);
  scrollTop();

  const me = store.getUser();

  if (!families.length) {
    setView(`
      <div class="page">
        ${invitations.length ? inviteCard(invitations) : ''}
        ${emptyState('🏠', '还没有家庭',
          invitations.length ? '有人邀请你加入家庭，到「消息」中同意即可'
                             : '创建一个家庭，然后邀请家人加入')}
        <button class="btn block" id="btn-create">创建家庭</button>
      </div>
    `);
    document.getElementById('btn-create').addEventListener('click', createFamily);
    bindInvites();
    return;
  }

  const html = families.map((f) => {
    const isOwner = me && f.owner_uid === me.uid;
    return `
      <a class="item" href="#/family/${encodeURIComponent(f.uid)}">
        <div class="thumb">🏠</div>
        <div class="grow">
          <div class="t">${escapeHtml(f.name)}
            ${isOwner ? '<span class="tag info">群主</span>' : ''}
          </div>
          <div class="s">${f.member_count} 位成员 · 创建于 ${fmtTime(f.created_at)}</div>
        </div>
        <span class="arrow">›</span>
      </a>`;
  }).join('');

  setView(`
    <div class="page">
      ${invitations.length ? inviteCard(invitations) : ''}
      <div class="card">
        <div class="card-bd tight">${html}</div>
      </div>
      <div class="note">
        家庭是设备与成员的归属单位。设备绑定到家庭后，家庭成员才能看到它。
      </div>
    </div>
  `);
  bindInvites();
}

/** 待处理邀请卡片（在家庭列表页直接可同意/拒绝，无需先进消息页） */
function inviteCard(list) {
  return `
    <div class="card">
      <div class="card-hd">
        <span class="grow">家庭邀请（${list.length}）</span>
      </div>
      <div class="card-bd tight">
        ${list.map((iv) => `
          <div class="item">
            <div class="thumb">✉️</div>
            <div class="grow">
              <div class="t">${escapeHtml(iv.family_name)}</div>
              <div class="s">${escapeHtml(iv.inviter_nickname)} 邀请你加入 · ${fmtRelative(iv.created_at)}</div>
            </div>
          </div>
          <div class="btn-row" style="padding:0 14px 12px">
            <button class="btn sm" data-inv-yes="${escapeHtml(iv.invitation_uid)}">同意加入</button>
            <button class="btn ghost sm" data-inv-no="${escapeHtml(iv.invitation_uid)}">拒绝</button>
          </div>`).join('')}
      </div>
    </div>`;
}

function bindInvites() {
  document.querySelectorAll('[data-inv-yes]').forEach((b) => {
    b.addEventListener('click', () => handleInvite(b.getAttribute('data-inv-yes'), true));
  });
  document.querySelectorAll('[data-inv-no]').forEach((b) => {
    b.addEventListener('click', () => handleInvite(b.getAttribute('data-inv-no'), false));
  });
}

async function handleInvite(invUid, accept) {
  const yes = await confirmBox(
    accept ? '同意加入家庭' : '拒绝邀请',
    accept ? '同意后你将成为该家庭成员，可以看到家庭内的设备与闹钟。'
           : '拒绝后不会加入该家庭。',
    { okText: accept ? '同意加入' : '拒绝', danger: !accept }
  );
  if (!yes) return;
  try {
    if (accept) await invitationApi.accept(invUid);
    else await invitationApi.reject(invUid);
    toastOk(accept ? '已加入家庭' : '已拒绝');
    render();
  } catch (e) {
    showError(e);
  }
}

async function createFamily() {
  const name = await promptBox('创建家庭', {
    label: '家庭名称',
    placeholder: '如：我们家',
    okText: '创建',
  });
  if (!name) return;
  if (name.length > 20) {
    showError({ message: '家庭名称最多 20 个字' });
    return;
  }
  try {
    const fam = await familyApi.create(name);
    toastOk('创建成功');
    go(`/family/${encodeURIComponent(fam.uid)}`);
  } catch (e) {
    showError(e);
  }
}
