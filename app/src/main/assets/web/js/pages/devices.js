/* 设备列表页：跨所有家庭汇总展示我的设备
 *
 * 后端 /devices 需要 family_uid 参数，所以这里先拉家庭列表再逐个拉设备。
 * 家庭的设备数一般很少（一个家庭 1-3 台），串行请求可接受；
 * 用 Promise.all 并发避免慢。
 */

import { familyApi, deviceApi } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import { skeleton, emptyState, showError, onlineText, platformName, errorState } from '../ui.js';
import * as store from '../store.js';
import { escapeHtml } from '../router.js';

export async function render() {
  setLayout({ title: '设备', tabbar: true, activeTab: '/devices' });
  setView(`<div class="page">${skeleton(3)}</div>`);

  let families = [];
  try {
    families = (await familyApi.list()) || [];
  } catch (e) {
    showError(e);
    setView(`<div class="page">${errorState(e, () => render())}</div>`);
    return;
  }

  // 并发拉每个家庭的设备
  const results = await Promise.all(
    families.map(async (f) => {
      try {
        const ds = (await deviceApi.list(f.uid)) || [];
        return ds.map((d) => ({ ...d, _familyName: f.name }));
      } catch {
        return [];
      }
    })
  );
  const devices = results.flat();
  scrollTop();

  const me = store.getUser();

  if (!devices.length) {
    setView(`
      <div class="page">
        ${emptyState('📱', '还没有设备',
                     families.length ? '进入家庭即可绑定设备' : '先创建一个家庭，再绑定设备')}
        ${families.length
          ? `<button class="btn block" id="go-fam">去家庭绑定</button>`
          : `<button class="btn block" id="go-create">创建家庭</button>`}
      </div>
    `);
    const b1 = document.getElementById('go-fam');
    if (b1) b1.addEventListener('click', () => go('/families'));
    const b2 = document.getElementById('go-create');
    if (b2) b2.addEventListener('click', () => go('/families'));
    return;
  }

  // 我拥有的设备排前面
  devices.sort((a, b) => Number(b.owner_is_me) - Number(a.owner_is_me));

  const html = devices.map((d) => {
    const on = onlineText(d.online_status);
    const mine = d.owner_is_me;
    return `
      <a class="item" href="#/device/${encodeURIComponent(d.uid)}">
        <div class="thumb">${d.platform === 'ios' ? '📱' : '🤖'}</div>
        <div class="grow">
          <div class="t">${escapeHtml(d.device_name)}
            <span class="tag ${on.cls}">${on.text}</span>
          </div>
          <div class="s">
            ${escapeHtml(platformName(d.platform))}
            · ${escapeHtml(d._familyName)}
            · ${mine ? '我的设备' : escapeHtml((d.owner_nickname || '他人') + ' 的设备')}
          </div>
        </div>
        <span class="arrow">›</span>
      </a>`;
  }).join('');

  const mineCount = devices.filter((d) => d.owner_is_me).length;

  setView(`
    <div class="page">
      <div class="card">
        <div class="card-hd">我的设备（${mineCount}）</div>
        <div class="card-bd tight">${
          devices.filter((d) => d.owner_is_me).map((d) => row(d)).join('')
          || '<div class="card-bd muted small">暂无</div>'
        }</div>
      </div>
      ${devices.filter((d) => !d.owner_is_me).length ? `
      <div class="card">
        <div class="card-hd">家人的设备（${devices.filter((d) => !d.owner_is_me).length}）</div>
        <div class="card-bd tight">${
          devices.filter((d) => !d.owner_is_me).map((d) => row(d)).join('')
        }</div>
      </div>` : ''}
      <div class="note">
        点设备进入详情，可查看闹钟、管理信任成员与查看授权。
      </div>
    </div>
  `);

  function row(d) {
    const on = onlineText(d.online_status);
    return `
      <a class="item" href="#/device/${encodeURIComponent(d.uid)}">
        <div class="thumb">${d.platform === 'ios' ? '📱' : '🤖'}</div>
        <div class="grow">
          <div class="t">${escapeHtml(d.device_name)}
            <span class="tag ${on.cls}">${on.text}</span>
          </div>
          <div class="s">${escapeHtml(platformName(d.platform))} · ${escapeHtml(d._familyName)}</div>
        </div>
        <span class="arrow">›</span>
      </a>`;
  }
}
