package com.trustalarm.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.components.TaCard
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 登录 / 注册页 —— 对应 `web/js/pages/login.js`。
 *
 * 布局与网页版逐块对应：
 *   1. 顶部品牌区（⏰ + 「信任闹钟」+ 副标题）
 *   2. 表单卡片（手机号 / 密码 / 注册时多一个昵称 / 行内错误 / 提交按钮 / 切换登录注册）
 *   3. 演示账号卡片（点一下自动填入）
 *   4. 底部说明（服务地址 + 网络排查提示）
 *
 * ## 与网页版的两个行为差异（都是刻意的）
 *
 * 1. **不显示 `location.origin`** —— 那是浏览器的概念，原生侧没有。
 *    改为显示配置的后端地址（BuildConfig.API_BASE），作用相同：
 *    让用户能看到「App 在连哪个服务器」，排查连不上问题时非常有用。
 *
 * 2. **加 `imePadding()`** —— 键盘弹出时把内容顶起来，避免输入框被键盘遮住。
 *    网页版靠浏览器自己处理，原生侧必须显式处理。
 */
@Composable
fun LoginScreen(
    vm: LoginViewModel,
    apiBase: String,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val c = TaTheme.colors
    val ty = MaterialTheme.typography

    // 密码明文/遮蔽切换（用户要求：登录密码输入要有可见与不可见按钮）
    var passwordVisible by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ---- 1. 品牌区 ----
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("⏰", style = ty.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "信任闹钟",
                style = ty.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = c.text,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (s.isRegister) "注册新账号" else "登录以管理家庭闹钟",
                style = ty.bodySmall,
                color = c.text3,
            )
        }

        // ---- 2. 表单卡片 ----
        TaCard {
            Column {
                OutlinedTextField(
                    value = s.phone,
                    onValueChange = vm::onPhoneChange,
                    label = { Text("手机号") },
                    placeholder = { Text("11 位手机号") },
                    singleLine = true,
                    enabled = !s.busy,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next,
                    ),
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = s.password,
                    onValueChange = vm::onPasswordChange,
                    label = { Text("密码") },
                    placeholder = {
                        Text(if (s.isRegister) "8-64 位，含字母和数字" else "请输入密码")
                    },
                    singleLine = true,
                    enabled = !s.busy,
                    // ★ 可见/不可见切换（用户要求）：默认遮蔽，点右侧「眼睛」图标切换明文。
                    //   长辈手输密码看不清时非常需要——本项目面向家庭用户，
                    //   这是刚需而不是锦上添花。
                    visualTransformation = if (passwordVisible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        androidx.compose.material3.IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            enabled = !s.busy,
                        ) {
                            Text(
                                if (passwordVisible) "🙈" else "👁",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (s.isRegister) ImeAction.Next else ImeAction.Done,
                    ),
                )

                if (s.isRegister) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = s.nickname,
                        onValueChange = vm::onNicknameChange,
                        label = { Text("昵称") },
                        placeholder = { Text("如：爸爸") },
                        singleLine = true,
                        enabled = !s.busy,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                }

                // 行内错误（网页版 #f-err）：不消失，比 toast 更容易看清
                if (!s.errorText.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(s.errorText!!, style = ty.bodySmall, color = c.err)
                }

                Spacer(Modifier.height(14.dp))
                TaButton(
                    text = if (s.isRegister) "注册并登录" else "登录",
                    onClick = vm::submit,
                    loading = s.busy,
                )
            }
        }

        // ---- 3. 切换登录/注册 ----
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = vm::toggleMode, enabled = !s.busy) {
                Text(
                    if (s.isRegister) "已有账号？去登录" else "没有账号？去注册",
                    color = c.primary,
                    style = ty.bodyMedium,
                )
            }
        }

        // ---- 4. 演示账号 ----
        Spacer(Modifier.height(4.dp))
        TaCard {
            Column {
                Text("演示账号（密码与网页版一致）", style = ty.bodySmall, color = c.text3)
                Spacer(Modifier.height(8.dp))
                // 三个演示账号按行排（原生侧窄屏下比网页版 flex-wrap 更可控）
                LoginViewModel.DEMO_ACCOUNTS.forEach { (phone, label, desc) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TaButton(
                            text = label,
                            onClick = { vm.fillDemo(phone) },
                            style = TaButtonStyle.Ghost,
                            enabled = !s.busy,
                            fillWidth = false,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(desc, style = ty.labelSmall, color = c.text3)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "点一下自动填入手机号与密码",
                    style = ty.labelSmall,
                    color = c.text3,
                )
            }
        }

        // ---- 5. 底部说明（替代网页版的 location.origin）----
        Spacer(Modifier.height(14.dp))
        Text(
            "服务地址：$apiBase\n若登录失败，请确认手机与电脑在同一网络、电脑未休眠。",
            style = ty.labelSmall,
            color = c.text3,
        )
        Spacer(Modifier.height(24.dp))
    }
}
