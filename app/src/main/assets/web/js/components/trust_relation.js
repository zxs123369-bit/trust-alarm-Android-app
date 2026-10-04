/* 信任关系展示 + 开关操作（双向语义）
 *
 * 用户要求（两步）：
 *   第一步：信任开关要在成员详情里展示，说清方向
 *   第二步：光展示不够 —— 要能开与关
 *
 * 后端 member detail 返回的结构：
 *   devices[]: {
 *     uid, device_name, platform, owner_is_me,
 *     auto_alarm_trust_switch,    该设备的信任自动下发开关
 *     trusted_by_me,              我是否信任了 TA（在这台设备上）
 *     trusted_me,                 TA 是否信任了我（在这台设备上）
 *     my_permission,
 *   }
 *   trust: { i_trust_target, target_trusts_me, i_trust_detail[], ... }
 *
 * ★ 权限：只有**设备归属人**能改该设备的信任开关与名单。
 *   所以开关控件只在 owner_is_me 的设备上可点，别人的设备显示只读状态。
 */
import { trustApi } from '../api.js';

/** 渲染「我的设备上的信任」区（可操作） */
export function myDevicesTrustCard(d, esc, onChanged) {
  if (d.is_me) return '';
  // 用 my_devices（我名下设备）而不是 devices（该成员名下设备）——
  // 只有我自己的设备我才能改信任设置。
  const myDevs = d.my_devices || [];

  if (!myDevs.length) {
    return `
      <div class="card">
        <div class="card-hd"><span class="grow">我的设备对他的信任</span></div>
        <div class="card-bd">
          <div class="hint">你名下还没有设备，因此无法信任他人。</div>
        </div>
      </div>`;
  }

  const him = esc(d.nickname);

  const rows = myDevs.map((dev) => {
    const on = dev.auto_alarm_trust_switch === 1;
    const trusted = dev.trusted_by_me === true;
    return `
      <div class="rel-row">
        <div class="rel-icon">${dev.platform === 'ios' ? '📱' : '🤖'}</div>
        <div class="grow">
          <div class="t">${esc(dev.device_name)}
            <span class="tag ${on ? 'ok' : ''}">${on ? '信任开关已开' : '信任开关已关'}</span>
            ${trusted ? '<span class="tag ok">已信任他</span>' : `<span class="tag">未信任 ${him}</span>`}
          </div>
          <div class="s">${
            trusted && on
              ? `${him} 可以在这台设备上免确认设置闹钟`
              : trusted
                ? `已加入信任名单，但开关关闭 → 仍需你确认`
                : `加入信任名单并开启开关后，${him} 可免确认在这台设备设闹钟`
          }</div>
        </div>
      </div>
      <div class="btn-row" style="padding:0 14px 12px">
        <button class="btn ${trusted ? 'ghost' : ''} sm"
                data-trust-add="${esc(dev.uid)}" data-name="${esc(dev.device_name)}"
                ${trusted ? 'disabled' : ''}>
          ${trusted ? '已在信任名单' : '加入信任名单'}
        </button>
        <button class="btn ghost sm"
                data-trust-remove="${esc(dev.uid)}" data-name="${esc(dev.device_name)}"
                ${trusted ? '' : 'disabled'}>
          移出信任名单
        </button>
        <button class="btn ${on ? 'ghost' : ''} sm"
                data-switch="${esc(dev.uid)}" data-on="${on ? '1' : '0'}"
                data-name="${esc(dev.device_name)}">
          ${on ? '关闭信任开关' : '开启信任开关'}
        </button>
      </div>`;
  }).join('');

  return `
    <div class="card">
      <div class="card-hd"><span class="grow">我的设备对他的信任（可设置）</span></div>
      <div class="card-bd tight">${rows}</div>
      <div class="card-bd">
        <div class="note">
          这里是**你名下设备**对 ${him} 的信任设置 ——
          设置后 ${him} 可以操作你的设备。
          <br>与「${him} 是否信任你」是两件独立的事，互不影响。
        </div>
      </div>
    </div>`;
}

/** 渲染「他的设备对我的信任」区（只读，无权限改） */
export function hisDevicesTrustCard(d, esc) {
  if (d.is_me) return '';
  // 他的设备 = devices（该成员名下），信任状态用 trust 明细里对应设备的 switch_on
  const hisDevs = d.devices || [];
  if (!hisDevs.length) return '';
  const him = esc(d.nickname);

  // 从双向信任明细里取「他信任我」的每台设备状态
  const detailMap = new Map();
  ((d.trust || {}).target_trusts_detail || []).forEach((x) => {
    detailMap.set(x.device_uid, x);
  });

  const rows = hisDevs.map((dev) => {
    const det = detailMap.get(dev.uid);
    const trustedMe = !!det;
    const on = det ? det.switch_on : false;
    return `
      <div class="rel-row ${trustedMe ? 'on' : ''}">
        <div class="rel-icon">${dev.platform === 'ios' ? '📱' : '🤖'}</div>
        <div class="grow">
          <div class="t">${esc(dev.device_name)}
            <span class="tag ${trustedMe ? 'ok' : ''}">${trustedMe ? '已信任你' : '未信任你'}</span>
            ${trustedMe ? `<span class="tag ${on ? 'ok' : ''}">${on ? '已生效' : '开关关'}</span>` : ''}
          </div>
          <div class="s">${
            trustedMe && on
              ? `你可以在这台设备上免确认设置闹钟`
              : trustedMe
                ? `他在信任名单中，但开关关闭 → 你设闹钟仍需他确认`
                : `需要 ${him} 把他自己设备上的信任名单加入你，你才能操作`
          }</div>
        </div>
      </div>`;
  }).join('');

  return `
    <div class="card">
      <div class="card-hd"><span class="grow">${him} 的设备对你的信任（只读）</span></div>
      <div class="card-bd tight">${rows}</div>
      <div class="card-bd">
        <div class="note">
          这些是 ${him} 名下设备的设置，只有 TA 本人能改。
          你无法在这里开关 —— 需要 TA 在自己的设备里把你加入信任名单。
        </div>
      </div>
    </div>`;
}

/**
 * 绑定信任相关按钮事件。
 * @param d 成员详情数据
 * @param after 操作成功后回调（用于刷新页面）
 * @param toastOk toast 成功提示
 * @param showError 错误展示
 */
export function bindTrustActions(d, { after, toastOk, showError, confirmBox }) {
  // 加入信任名单
  document.querySelectorAll('[data-trust-add]').forEach((b) => {
    b.addEventListener('click', async () => {
      const devUid = b.getAttribute('data-trust-add');
      try {
        await trustApi.addByUid(devUid, d.uid);
        toastOk(`已把【${d.nickname}】加入信任名单`);
        after();
      } catch (e) {
        showError(e);
      }
    });
  });

  // 移出信任名单
  document.querySelectorAll('[data-trust-remove]').forEach((b) => {
    b.addEventListener('click', async () => {
      const devUid = b.getAttribute('data-trust-remove');
      const devName = b.getAttribute('data-name');
      const yes = await confirmBox(
        '移出信任名单',
        `移出后【${d.nickname}】将无法操作【${devName}】。`,
        { okText: '移出', danger: true }
      );
      if (!yes) return;
      try {
        await trustApi.remove(devUid, d.uid);
        toastOk('已移出信任名单');
        after();
      } catch (e) {
        showError(e);
      }
    });
  });

  // 开关信任自动下发
  document.querySelectorAll('[data-switch]').forEach((b) => {
    b.addEventListener('click', async () => {
      const devUid = b.getAttribute('data-switch');
      const on = b.getAttribute('data-on') === '1';
      const devName = b.getAttribute('data-name');
      const yes = await confirmBox(
        on ? '关闭信任开关' : '开启信任开关',
        on
          ? `关闭后，信任成员在【${devName}】上设闹钟仍需你确认。`
          : `开启后，信任名单中的成员可在【${devName}】上免确认设置闹钟。`,
        { okText: on ? '关闭' : '开启' }
      );
      if (!yes) return;
      try {
        await trustApi.setSwitch(devUid, !on);
        toastOk(on ? '已关闭信任开关' : '已开启信任开关');
        after();
      } catch (e) {
        showError(e);
      }
    });
  });
}
