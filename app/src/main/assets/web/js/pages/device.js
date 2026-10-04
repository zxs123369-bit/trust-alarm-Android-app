/* 设备详情页
 *
 * P2 范围：设备信息展示 + 改名 + 解绑 + 各功能入口
 * P3 起逐步填充：闹钟列表（P3）、信任名单（P4）、查看授权（P4）
 *
 * 展示控制依据后端返回的 view_source / owner_is_me，
 * 不自己重算权限规则（规则只在 app/core/permissions.py 里）。
 */

import { deviceApi } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import {
  skeleton, emptyState, promptBox, confirmBox, showError, toastOk,
  onlineText, platformName, fmtRelative, errorState } from '../ui.js';
import { escapeHtml } from '../router.js';

let deviceUid = null;
let device = null;

export async function render(params) {
  deviceUid = params.uid;
  setLayout({ title: '设备', backBtn: true, tabbar: false });
  setView(`<div class="page">${skeleton(4)}</div>`);

  try {
    device = await deviceApi.detail(deviceUid);
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
  const d = device;
  const isOwner = d.owner_is_me;
  const on = onlineText(d.online_status);

  setLayout({
    title: d.device_name,
    backBtn: true,
    tabbar: false,
    actions: isOwner ? [{ text: '设置', onClick: () => ownerMenu(d) }] : [],
  });

  setView(`
    <div class="page">
      <div class="card">
        <div class="card-bd">
          <div class="row" style="gap:12px">
            <div class="thumb" style="width:52px;height:52px;font-size:24px">
              ${d.platform === 'ios' ? '📱' : '🤖'}
            </div>
            <div class="grow">
              <div style="font-size:17px;font-weight:600">${escapeHtml(d.device_name)}</div>
              <div class="muted small mt8">
                ${escapeHtml(platformName(d.platform))} ·
                <span class="tag ${on.cls}">${on.text}</span>
              </div>
            </div>
          </div>

          <div class="mt12 small muted" style="line-height:2">
            <div class="row between">
              <span>型号</span><span>${escapeHtml(d.model_display || '—')}</span>
            </div>
            <div class="row between">
              <span>归属人</span>
              <span>${d.owner_is_me ? '我' : escapeHtml(d.owner_nickname || '—')}</span>
            </div>
            <div class="row between">
              <span>最近心跳</span>
              <span>${d.last_heartbeat_at ? fmtRelative(d.last_heartbeat_at) : '从未上报'}</span>
            </div>
            <div class="row between">
              <span>信任自动下发</span>
              <span>${d.auto_alarm_trust_switch === 1 ? '已开启' : '已关闭'}</span>
            </div>
            <div class="row between">
              <span>我的权限</span>
              <span>${viewSourceText(d.view_source)}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-hd">功能</div>
        <div class="card-bd tight">
          <a class="item" href="#/device/${encodeURIComponent(d.uid)}/alarms">
            <div class="thumb">⏰</div>
            <div class="grow">
              <div class="t">闹钟</div>
              <div class="s">查看与设置该设备的闹钟</div>
            </div>
            <span class="arrow">›</span>
          </a>
          <a class="item" href="#/device/${encodeURIComponent(d.uid)}/logs">
            <div class="thumb">📋</div>
            <div class="grow">
              <div class="t">操作日志</div>
              <div class="s">谁在什么时候做了哪些操作</div>
            </div>
            <span class="arrow">›</span>
          </a>
          ${isOwner ? `
          <a class="item" href="#/device/${encodeURIComponent(d.uid)}/trust">
            <div class="thumb">🤝</div>
            <div class="grow">
              <div class="t">信任名单</div>
              <div class="s">信任成员可免确认下发并查看闹钟</div>
            </div>
            <span class="arrow">›</span>
          </a>
          <a class="item" href="#/device/${encodeURIComponent(d.uid)}/grants">
            <div class="thumb">👁️</div>
            <div class="grow">
              <div class="t">查看授权</div>
              <div class="s">单独授权他人只读查看</div>
            </div>
            <span class="arrow">›</span>
          </a>` : `
          <button class="item" id="btn-request" style="border:none">
            <div class="thumb">✋</div>
            <div class="grow">
              <div class="t">申请查看</div>
              <div class="s">向设备主人申请只读查看权限</div>
            </div>
            <span class="arrow">›</span>
          </button>`}
        </div>
      </div>

      ${isOwner ? `
      <div class="card">
        <div class="card-hd">设备管理</div>
        <div class="card-bd">
          <button class="btn ghost block mb8" id="btn-rename">重命名设备</button>
          <button class="btn danger-ghost block" id="btn-unbind">解绑设备</button>
          <div class="hint">解绑后该设备及其闹钟将从家庭中移除</div>
        </div>
      </div>` : `
      <div class="note warn">
        你是通过「${viewSourceText(d.view_source)}」获得访问权限的。
        仅设备归属人可管理该设备。
      </div>`}

      <div class="note mt12 mono brk">设备 ID：${escapeHtml(d.uid)}</div>
    </div>
  `);

  const rb = document.getElementById('btn-rename');
  if (rb) rb.addEventListener('click', () => rename(d));
  const ub = document.getElementById('btn-unbind');
  if (ub) ub.addEventListener('click', () => unbind(d));
  const rq = document.getElementById('btn-request');
  if (rq) rq.addEventListener('click', () => requestView(d));
}

/** view_source 转成用户能懂的说法（PRD §3.2 的六个来源） */
function viewSourceText(vs) {
  return {
    owner: '设备归属人',
    family_owner: '家庭群主',
    trust_member: '信任成员',
    granted: '已授权查看',
    creator: '闹钟创建人',
    none: '无查看权限',
  }[vs] || (vs || '—');
}

async function rename(d) {
  const name = await promptBox('重命名设备', {
    label: '新名称',
    value: d.device_name,
    okText: '保存',
  });
  if (!name || name === d.device_name) return;
  if (name.length > 20) {
    showError({ message: '设备名称最多 20 个字' });
    return;
  }
  try {
    const updated = await deviceApi.rename(d.uid, name);
    device = { ...device, ...updated };
    toastOk('已保存');
    draw();
  } catch (e) {
    showError(e);
  }
}

async function unbind(d) {
  const yes = await confirmBox(
    '解绑设备',
    `确定解绑「${d.device_name}」？\n该设备上的闹钟将不再可见，此操作不可撤销。`,
    { okText: '解绑', danger: true }
  );
  if (!yes) return;
  try {
    await deviceApi.unbind(d.uid);
    toastOk('已解绑');
    go('/devices', { replace: true });
  } catch (e) {
    showError(e);
  }
}

async function requestView(d) {
  const yes = await confirmBox(
    '申请查看',
    `向「${escapeHtml(d.device_name)}」的设备主人申请查看权限？\n获得后你可以看到该设备的闹钟列表（只读）。`,
    { okText: '发送申请' }
  );
  if (!yes) return;
  try {
    const { grantApi } = await import('../api.js');
    await grantApi.request(d.uid);
    toastOk('申请已发送，等待设备主人处理');
  } catch (e) {
    showError(e);
  }
}

function ownerMenu(d) {
  import('../ui.js').then(({ openModal }) => {
    const body = document.createElement('div');
    body.className = 'small muted';
    body.textContent = '设备归属人可管理信任名单、查看授权与设备本身。';
    openModal({
      title: '设备设置',
      body,
      center: true,
      actions: [
        { text: '重命名', kind: 'ghost', onClick: () => rename(d) },
        { text: '解绑设备', kind: 'danger', onClick: () => unbind(d) },
      ],
    });
  });
}
