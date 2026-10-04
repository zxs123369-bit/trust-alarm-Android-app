/* 登录 / 注册页 */

import { authApi, setTokens, clearTokens, getRefreshToken } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import { toast, toastOk, showError, loadingBlock } from '../ui.js';
import * as store from '../store.js';
import { escapeHtml } from '../router.js';

let mode = 'login';   // 'login' | 'register'
let busy = false;

export async function render() {
  setLayout({ title: '信任闹钟', tabbar: false });
  draw();
  scrollTop();
}

function draw() {
  const isLogin = mode === 'login';
  setView(`
    <div class="page">
      <div class="center" style="padding: 28px 0 18px">
        <div style="font-size:44px">⏰</div>
        <div style="font-size:19px;font-weight:600;margin-top:6px">信任闹钟</div>
        <div class="muted small mt8">
          ${isLogin ? '登录以管理家庭闹钟' : '注册新账号'}
        </div>
      </div>

      <div class="card">
        <div class="card-bd">
          <div class="field">
            <label>手机号</label>
            <input id="f-phone" type="tel" inputmode="numeric" maxlength="11"
                   placeholder="11 位手机号" autocomplete="username">
          </div>
          <div class="field">
            <label>密码</label>
            <input id="f-pwd" type="password" placeholder="${isLogin ? '请输入密码' : '8-64 位，含字母和数字'}"
                   autocomplete="${isLogin ? 'current-password' : 'new-password'}">
          </div>
          ${isLogin ? '' : `
          <div class="field">
            <label>昵称</label>
            <input id="f-nick" type="text" maxlength="20" placeholder="如：爸爸">
          </div>`}
          <div id="f-err" class="err-text hidden"></div>
          <button id="f-submit" class="btn block mt12">${isLogin ? '登录' : '注册并登录'}</button>
        </div>
        <div class="card-ft" style="justify-content:center">
          <button id="f-toggle" class="btn ghost sm">
            ${isLogin ? '没有账号？去注册' : '已有账号？去登录'}
          </button>
        </div>
      </div>

      <div class="card">
        <div class="card-bd">
          <div class="small muted">演示账号（密码 secret123）</div>
          <div class="row wrap mt8" style="gap:6px">
            <button class="btn ghost sm" data-fill="13700137000">爸爸（群主）</button>
            <button class="btn ghost sm" data-fill="13800138000">儿子（成员）</button>
            <button class="btn ghost sm" data-fill="13900139000">妈妈（成员）</button>
          </div>
          <div class="hint">点一下自动填入手机号与密码</div>
        </div>
      </div>

      <div class="note mt12">
        服务地址：<span class="mono">${escapeHtml(location.origin)}</span><br>
        若登录失败，请确认手机与电脑在同一网络、电脑未休眠。
      </div>
    </div>
  `);

  const phone = document.getElementById('f-phone');
  const pwd = document.getElementById('f-pwd');
  const nick = document.getElementById('f-nick');
  const submit = document.getElementById('f-submit');
  const errBox = document.getElementById('f-err');

  document.getElementById('f-toggle').addEventListener('click', () => {
    mode = isLogin ? 'register' : 'login';
    draw();
  });

  document.querySelectorAll('[data-fill]').forEach((b) => {
    b.addEventListener('click', () => {
      phone.value = b.getAttribute('data-fill');
      pwd.value = 'secret' + '123';
      if (!isLogin && nick) nick.value = b.textContent.split('（')[0];
      errBox.classList.add('hidden');
    });
  });

  const submitForm = async () => {
    const p = phone.value.trim();
    const pw = pwd.value;
    errBox.classList.add('hidden');

    if (!/^1[3-9]\d{9}$/.test(p)) {
      return showFieldErr('请输入正确的 11 位手机号');
    }
    if (!pw) return showFieldErr('请输入密码');
    if (!isLogin && !nick.value.trim()) return showFieldErr('请填写昵称');

    if (busy) return;
    busy = true;
    submit.disabled = true;
    submit.textContent = isLogin ? '登录中…' : '注册中…';

    try {
      const data = isLogin
        ? await authApi.login(p, pw)
        : await authApi.register(p, pw, nick.value.trim());

      setTokens(data.access_token, data.refresh_token);
      store.setUser(data.user);
      toastOk(isLogin ? '登录成功' : '注册成功');
      go('/local-alarms', { replace: true });   // 新主页：本地闹钟
    } catch (e) {
      showError(e);
      showFieldErr(e.message || '操作失败');
    } finally {
      busy = false;
      submit.disabled = false;
      submit.textContent = isLogin ? '登录' : '注册并登录';
    }
  };

  function showFieldErr(msg) {
    errBox.textContent = msg;
    errBox.classList.remove('hidden');
  }

  submit.addEventListener('click', submitForm);
  [phone, pwd, nick].forEach((el) => {
    if (!el) return;
    el.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') submitForm(); });
  });

  // 焦点
  setTimeout(() => { (phone.value ? pwd : phone).focus(); }, 100);
}

/** 供 main.js 在启动时判断是否需要先登录 */
export function needLogin() {
  return !store.getUser();
}
