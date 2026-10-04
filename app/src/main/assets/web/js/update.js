/* App 自更新（原生壳专属）
 *
 * 浏览器里不存在这件事（网页刷新即最新），所以只在原生壳生效。
 *
 * 流程：
 *   1. 页面启动时 checkUpdate()，发现新版本弹提示
 *   2. 用户点「立即更新」→ startUpdate() 下载
 *   3. 下载完成由原生拉起系统安装器，用户点一次「安装」
 *
 * 为什么不能全自动：Android 安全策略要求安装 APK 必须用户确认。
 * 真正的静默升级需要上应用商店。
 */

import { toast } from './ui.js';

/** 原生更新桥（不在壳里则为 null） */
const updater = (typeof window !== 'undefined' && window.TrustAlarmUpdater)
  ? window.TrustAlarmUpdater
  : null;

export const canSelfUpdate = !!updater;

/** 本次会话已提示过，避免反复弹窗打扰 */
let prompted = false;

/**
 * 检查并（可选）提示更新。
 *
 * @param {object} [opts]
 * @param {boolean} [opts.silent] 静默模式：无更新时不提示（启动时用）
 * @param {boolean} [opts.force]  用户手动点「检查更新」时用，任何结果都给反馈
 */
export async function checkAppUpdate({ silent = false, force = false } = {}) {
  if (!updater) {
    if (force) toast('当前环境不支持自动更新', 'info');
    return null;
  }

  let info;
  try {
    info = JSON.parse(updater.checkUpdate() || '{}');
  } catch (e) {
    if (force) toast('检查更新失败', 'danger');
    return null;
  }

  if (info.error) {
    if (force) toast(`检查更新失败：${info.error}`, 'danger');
    return null;
  }

  if (!info.hasUpdate) {
    if (force) toast(`已是最新版本 v${info.currentVersion || ''}`, 'ok');
    return info;
  }

  // 有更新：本次会话只自动提示一次；用户手动检查则总是提示
  if (!force && prompted) return info;
  prompted = true;

  const sizeMb = info.size ? `（${(info.size / 1048576).toFixed(1)} MB）` : '';
  const notes = (info.notes || '').trim();

  const { openModal } = await import('./ui.js');
  const body = document.createElement('div');
  body.innerHTML = `
    <div class="note ok-note">
      新版本 <b>v${escapeHtml(info.versionName)}</b>${sizeMb} 可用，
      当前 v${escapeHtml(info.currentVersion || '')}。
    </div>
    ${notes ? `<div class="note" style="white-space:pre-wrap">${escapeHtml(notes)}</div>` : ''}
    <div class="hint">
      点「立即更新」会自动下载，随后系统会弹出安装界面，点一下「安装」即可完成。
    </div>
  `;

  openModal({
    title: '发现新版本',
    body,
    center: true,
    actions: [
      { text: '稍后', kind: 'ghost' },
      {
        text: '立即更新',
        onClick: () => {
          const ok = updater.startUpdate();
          toast(
            ok ? '已开始下载，完成后会提示安装' : '启动更新失败，请稍后重试',
            ok ? 'ok' : 'danger',
            4000,
          );
        },
      },
    ],
  });

  return info;
}

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}
