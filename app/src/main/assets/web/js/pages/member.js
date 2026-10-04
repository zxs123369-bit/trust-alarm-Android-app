/* 成员详情页（以成员为主体看他的设备与权限）
 *
 * 用户要求：设备和成员是绑定的。点成员应看到该成员的设备等信息，
 * 而不是「成员一列、设备一列」两个割裂的列表。一个人可能有多台设备。
 *
 * 后端契约（GET /families/{fid}/members/{uid}）：
 *   {
 *     uid, nickname, phone_masked, role, is_owner, joined_at, is_me,
 *     device_count, devices: [{uid, device_name, platform, online_status,
 *                             owner_is_me, owner_nickname, my_permission}],
 *     trust_member_count, grant_count, can_view_all_devices, my_nickname
 *   }
 *
 * my_permission 取值（与后端 permissions.view_source 对齐）：
 *   owner          我是设备归属人
 *   family_owner   我是群主
 *   trusted        我是信任成员
 *   granted        我被单独授权
 *   alarm_creator  我是闹钟创建者
 *   need_request   需要申请
 */
import { api } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import { escapeHtml } from '../router.js';
import {
  skeleton, emptyState, showError, toastOk, confirmBox,
  fmtRelative, platformName, onlineText,
} from '../ui.js';
import * as store from '../store.js';
import {
  myDevicesTrustCard, hisDevicesTrustCard, bindTrustActions,
} from '../components/trust_relation.js';

/** 权限标签 → 展示文案与样式 */
const PERM = {
  owner: { text: '设备归属人', cls: 'ok', desc: '这是你的设备' },
  family_owner: { text: '群主', cls: '', desc: '你是群主，可查看该设备' },
  trusted: { text: '信任成员', cls: 'ok', desc: '你在此设备信任名单中' },
  granted: { text: '已授权查看', cls: '', desc: '设备主人已授权你查看' },
  alarm_creator: { text: '闹钟创建者', cls: '', desc: '你在该设备上建过闹钟' },
  need_request: { text: '需申请', cls: 'warn', desc: '你尚未获得该设备的查看权限' },
  unknown: { text: '未知', cls: '', desc: '' },
};

const ROLE = {
  owner: { text: '群主', cls: 'ok' },
  member: { text: '成员', cls: '' },
};

/** 路由参数：router.js 直接把 params 传给页面（不是 ctx.params） */
export async function render(params) {
  const familyUid = params.uid;
  const memberUid = params.memberUid;

  setLayout({ title: '成员详情', back: `#/family/${encodeURIComponent(familyUid)}`, tabbar: false });
  setView(skeleton(3));

  let m;
  try {
    m = await memberDetail(familyUid, memberUid);
  } catch (e) {
    showError(e);
    setView(`
      <div class="page">
        ${emptyState('😕', '打不开该成员', '可能已不在这个家庭，或你没有查看权限')}
        <button class="btn block" id="btn-back">返回家庭</button>
      </div>`);
    document.getElementById('btn-back')
      .addEventListener('click', () => go(`/family/${encodeURIComponent(familyUid)}`));
    return;
  }

  draw(familyUid, memberUid, m);
  scrollTop();
}

async function memberDetail(familyUid, memberUid) {
  return api.get(
    `/families/${encodeURIComponent(familyUid)}/members/${encodeURIComponent(memberUid)}`
  );
}

function draw(familyUid, memberUid, m) {
  const me = store.getUser();
  const isMe = m.is_me || (me && me.uid === m.uid);
  const role = ROLE[m.role] || { text: m.role, cls: '' };

  // ---- 该成员的设备（核心：一人可多台）----
  const devHtml = m.device_count
    ? m.devices.map((d) => {
        const on = onlineText(d.online_status);
        const perm = PERM[d.my_permission] || PERM.unknown;
        return `
          <a class="item" href="#/device/${encodeURIComponent(d.uid)}">
            <div class="thumb">${d.platform === 'ios' ? '📱' : '🤖'}</div>
            <div class="grow">
              <div class="t">${escapeHtml(d.device_name)}
                <span class="tag ${on.cls}">${on.text}</span>
              </div>
              <div class="s">${escapeHtml(platformName(d.platform))}</div>
              <div class="s">
                <span class="tag ${perm.cls}">${perm.text}</span>
                <span class="muted"> ${escapeHtml(perm.desc)}</span>
              </div>
            </div>
            <span class="arrow">›</span>
          </a>
          <div class="btn-row" style="padding:0 14px 12px">
            <a class="btn ghost sm"
               href="#/device/${encodeURIComponent(d.uid)}/logs">查看该设备的操作日志</a>
          </div>`;
      }).join('')
    : emptyState('📱', '名下还没有设备', isMe ? '去设备页绑定一台' : '该成员还没有绑定设备');

  setView(`
    <div class="page">
      <!-- 成员基本信息 -->
      <div class="card">
        <div class="card-bd">
          <div class="profile">
            <div class="avatar">${m.is_owner ? '👑' : '👤'}</div>
            <div class="grow">
              <div class="name">${escapeHtml(m.nickname)}
                ${isMe ? '<span class="tag">我</span>' : ''}
              </div>
              <div class="sub">${escapeHtml(m.phone_masked || '')}</div>
              <div class="sub mt6">
                <span class="tag ${role.cls}">${role.text}</span>
                <span class="tag">加入于 ${fmtRelative(m.joined_at)}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 名下设备 -->
      <div class="card">
        <div class="card-hd">
          <span class="grow">名下设备（${m.device_count}）</span>
        </div>
        <div class="card-bd tight">${devHtml}</div>
      </div>

      <!-- 权限概览（解释"你能对他做什么"） -->
      <div class="card">
        <div class="card-hd"><span class="grow">你能查看他设备的权限</span></div>
        <div class="card-bd">
          ${m.can_view_all_devices
            ? `<div class="note ok-note">✓ 你可以查看该成员名下全部设备的闹钟</div>`
            : `<div class="note warn-note">
                 ⚠ 你无法查看该成员的部分或全部设备。需向设备主人申请查看权限，
                 或由设备主人把你加入信任名单。
               </div>`}
          ${m.trust_member_count
            ? `<div class="hint">该成员在其 ${m.trust_member_count} 台设备的信任名单中</div>`
            : ''}
          ${m.grant_count
            ? `<div class="hint">该成员持有 ${m.grant_count} 项查看授权</div>`
            : ''}
        </div>
      </div>

      ${myDevicesTrustCard(m, escapeHtml)}
      ${hisDevicesTrustCard(m, escapeHtml)}

      ${m.is_me ? `
      <div class="note">
        这是你自己。你的设备在「设备」页可以看到全部。
      </div>` : `
      <div class="note">
        设备归属是「个人」而非「家庭」—— 同一位成员的多台设备都在这里列出。
        点设备可查看它的闹钟、信任名单与查看授权。
      </div>`}
    </div>
  `);

  // 绑定信任操作（加入/移出信任名单、开关信任自动下发）
  bindTrustActions(m, {
    after: () => render({ uid: familyUid, memberUid }),
    toastOk, showError, confirmBox,
  });
}
