/* 后台可靠性设置页 —— 国产 ROM 上让闹钟「必定响」的关键一步
 *
 * ## 为什么需要单独一页
 *
 * Android 的闹钟要可靠响铃，需要三件事同时成立：
 *   ① 精确闹钟权限（Android 12+ 需手动授权）
 *   ② 不被系统电池优化压制（Doze / 省电模式会推迟唤醒）
 *   ③ 不被 ROM 的「清理后台」清掉（小米/华为/OPPO/vivo 会激进清理）
 *
 * ①②③ 都是**系统级开关，App 无权代改**，只能跳转引导用户手动开。
 * 缺任何一个都会表现为「闹钟不响 / 响得不准」，而这恰恰是用户最难自查的地方 ——
 * 所以做成一个显式页面，逐项显示状态 + 一键跳转对应设置。
 *
 * ## 设计取舍
 *
 * · 状态能自测的就自测（精确闹钟权限、电池白名单可查），
 *   不能自测的（自启动）就诚实标注「需手动确认」，不假装能检测。
 * · 厂商自启动页跳转**不可靠**（类名随版本变），所以：
 *   先试厂商页，失败自动退回系统「应用详情」页 —— 那是唯一稳定入口。
 */
import { setLayout, setView, go } from '../layout.js';
import { toast } from '../ui.js';
import { escapeHtml } from '../router.js';
import { isNativeShell, canScheduleExact } from '../native.js';
import {
  deviceManufacturer, isIgnoringBatteryOptimizations,
  openBatteryOptimizationSettings, openAppSettings, openAutoStartSettings,
} from '../native.js';

/** 按品牌给出「去哪儿找自启动开关」的具体提示 */
function autoStartHint(brand) {
  const b = String(brand || '').toLowerCase();
  if (b.includes('xiaomi') || b.includes('redmi')) {
    return '小米：应用详情 → 自启动（或「省电策略」选「无限制」）';
  }
  if (b.includes('huawei') || b.includes('honor')) {
    return '华为/荣耀：应用详情 → 耗电详情 → 应用启动管理 → 手动管理，三项全开';
  }
  if (b.includes('oppo') || b.includes('realme')) {
    return 'OPPO/realme：应用详情 → 耗电管理 → 允许后台运行 + 允许自启动';
  }
  if (b.includes('vivo') || b.includes('iqoo')) {
    return 'vivo/iQOO：应用详情 → 权限管理 → 自启动';
  }
  if (b.includes('samsung')) {
    return '三星：电池 → 后台使用限制 → 把本应用加入「永不休眠的应用」';
  }
  return '在「应用详情 → 电池 / 后台管理」里设为「不受限制」，并允许自启动';
}

export async function render() {
  setLayout({ title: '后台可靠性', tabbar: false });

  // 原生壳外（网页版）没有这些能力 —— 诚实提示，不显示假按钮
  if (!isNativeShell) {
    setView(`
      <div class="page">
        <div class="card" style="padding:16px;line-height:1.8">
          <div style="font-size:15px;font-weight:600;margin-bottom:8px">仅手机 App 可用</div>
          <div class="muted" style="font-size:14px">
            后台可靠性设置需要读取系统权限状态并跳转系统设置页，
            请在手机 App 里打开本页。
          </div>
        </div>
        <button class="btn block" id="btn-back">返回</button>
      </div>`);
    document.getElementById('btn-back').addEventListener('click', () => go('/settings'));
    return () => {};
  }

  const exactOk = !!canScheduleExact();
  const battOk = !!isIgnoringBatteryOptimizations();
  const brand = deviceManufacturer();

  const statusChip = (ok) => ok
    ? '<span style="color:#38a169;font-weight:600">已开启</span>'
    : '<span style="color:#e53e3e;font-weight:600">未开启</span>';

  setView(`
    <div class="page">
      <div class="card" style="padding:16px;line-height:1.75;margin-bottom:12px">
        <div style="font-size:15px;font-weight:600;margin-bottom:6px">为什么需要这些设置</div>
        <div class="muted" style="font-size:13.5px">
          闹钟要在息屏、后台、被系统省电时都能准时响，
          需要下面几项系统权限。任何一项没开，都可能出现
          <b>闹钟不响或晚响</b>。这几项都在系统设置里，需要你手动确认。
        </div>
      </div>

      <!-- 1. 精确闹钟 -->
      <div class="card" style="padding:16px;margin-bottom:10px">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div style="font-weight:600">1. 精确闹钟权限</div>
          ${statusChip(exactOk)}
        </div>
        <div class="muted" style="font-size:13px;margin-top:8px;line-height:1.7">
          没有它，系统会把闹钟「随便找个时间」触发，可能差几分钟甚至更久。
        </div>
        <button class="btn block" id="btn-exact" style="margin-top:10px"
          ${exactOk ? 'disabled' : ''}>
          ${exactOk ? '已开启，无需操作' : '去开启'}
        </button>
      </div>

      <!-- 2. 电池优化白名单 -->
      <div class="card" style="padding:16px;margin-bottom:10px">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div style="font-weight:600">2. 电池优化白名单</div>
          ${statusChip(battOk)}
        </div>
        <div class="muted" style="font-size:13px;margin-top:8px;line-height:1.7">
          加入白名单后，系统省电时不会冻结本应用，闹钟才能准时唤醒。
        </div>
        <button class="btn block" id="btn-batt" style="margin-top:10px"
          ${battOk ? 'disabled' : ''}>
          ${battOk ? '已在白名单中' : '去加入白名单'}
        </button>
      </div>

      <!-- 3. 自启动（无法自动检测，诚实说明） -->
      <div class="card" style="padding:16px;margin-bottom:10px">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div style="font-weight:600">3. 允许自启动 / 后台运行</div>
          <span class="muted" style="font-size:13px">需手动确认</span>
        </div>
        <div class="muted" style="font-size:13px;margin-top:8px;line-height:1.75">
          国产手机（小米/华为/OPPO/vivo 等）会主动清理后台，
          被清理后闹钟会停。请按下面提示开启自启动：<br>
          <span style="color:#2b6cb0">${escapeHtml(autoStartHint(brand))}</span>
        </div>
        <button class="btn block" id="btn-auto" style="margin-top:10px">打开设置</button>
        <button class="btn block" id="btn-appinfo"
          style="margin-top:8px;background:#edf2f7;color:#2d3748">打开应用详情（备选入口）</button>
      </div>

      <div class="card" style="padding:14px;line-height:1.7;margin-bottom:12px">
        <div class="muted" style="font-size:12.5px">
          提示：从「最近任务」里划掉本应用<b>不影响</b>闹钟；
          但点系统清理工具的「<b>强制停止 / 一键清理</b>」会清空闹钟，
          那种情况下<b>重新打开本应用即可自动恢复</b>。
        </div>
      </div>

      <button class="btn block" id="btn-back2">返回设置</button>
    </div>`);

  const on = (id, fn) => {
    const el = document.getElementById(id);
    if (el) el.addEventListener('click', fn);
  };

  on('btn-exact', () => {
    // 精确闹钟授权页由 native.js 的 openExactAlarmSettings 提供
    import('../native.js').then((m) => {
      m.openExactAlarmSettings();
      toast('已打开系统设置，请允许「闹钟和提醒」');
    });
  });

  on('btn-batt', () => {
    openBatteryOptimizationSettings();
    toast('请选择「允许」');
  });

  on('btn-auto', () => {
    // 先试厂商专有页；没有就退回应用详情页（唯一稳定入口）
    const opened = openAutoStartSettings();
    if (!opened) {
      openAppSettings();
      toast('已打开应用详情，请在其中找到「自启动 / 电池」');
    }
  });

  on('btn-appinfo', () => openAppSettings());
  on('btn-back2', () => go('/settings'));

  // 从系统设置返回时刷新状态（用户可能刚授权）
  const onVis = () => { if (!document.hidden) render(); };
  document.addEventListener('visibilitychange', onVis);

  return () => document.removeEventListener('visibilitychange', onVis);
}
