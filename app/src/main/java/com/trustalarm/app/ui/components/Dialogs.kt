package com.trustalarm.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 对话框 —— 对应 `web/js/ui.js` 的 `promptBox` / `confirmBox`。
 *
 * ## 为什么不用 `remember { AlertDialog(...) }` 直接写
 *
 * 网页版的这两个函数是 **Promise 形态**（`await promptBox(...)` 拿到用户输入），
 * 页面代码里到处是「弹窗 → 等结果 → 继续」的顺序逻辑。Compose 里没有 Promise，
 * 若每个页面自己用 `var show by remember` 管理，会散落几十处状态。
 * 这里提供一个**状态驱动的等价物**：把「弹窗当前该显示什么」收进一个
 * [DialogHost] 状态里，页面仍然按「请求 → 拿结果」的顺序写。
 *
 * 用法（对应网页版的 `await confirmBox(...)`）：
 * ```
 * val dlg = rememberDialogHost()
 * // ...
 * dlg.confirm("删除闹钟", "删除后不可恢复。", danger = true) { yes -> if (yes) doDelete() }
 * dlg.prompt("创建家庭", label = "家庭名称", placeholder = "如：我们家") { name ->
 *     if (name != null) create(name)
 * }
 * ```
 */

/** 对话框类型 */
internal sealed interface DialogSpec {
    data class Confirm(
        val title: String,
        val message: String,
        val okText: String,
        val danger: Boolean,
        val onResult: (Boolean) -> Unit,
    ) : DialogSpec

    data class Prompt(
        val title: String,
        val label: String,
        val placeholder: String,
        val initial: String,
        val okText: String,
        val inputType: KeyboardType,
        val maxLength: Int,
        val onResult: (String?) -> Unit,
    ) : DialogSpec
}

/**
 * 对话框宿主状态。
 *
 * 通过 [rememberDialogHost] 创建，用 [confirm] / [prompt] 请求弹窗。
 * 同一时刻只显示一个弹窗（与网页版的单 modal 一致）。
 */
class DialogHost internal constructor() {
    internal var current by mutableStateOf<DialogSpec?>(null)

    /** 确认框（对应 `confirmBox(title, message, {okText, danger})`）。回调拿到 true/false。 */
    fun confirm(
        title: String,
        message: String,
        okText: String = "确定",
        danger: Boolean = false,
        onResult: (Boolean) -> Unit,
    ) {
        current = DialogSpec.Confirm(title, message, okText, danger, onResult)
    }

    /**
     * 输入框（对应 `promptBox(title, {label, placeholder, value, okText})`）。
     *
     * 回调拿到：
     *   · 用户点了确定 → 输入文本（已 trim）
     *   · 取消 / 点遮罩关闭 → null
     *
     * @param maxLength 输入上限。网页版在调用处自己校验长度（如家庭名 ≤ 20），
     *   这里提供硬上限，避免用户输入超长后再报错。
     */
    fun prompt(
        title: String,
        label: String = "",
        placeholder: String = "",
        initial: String = "",
        okText: String = "确定",
        inputType: KeyboardType = KeyboardType.Text,
        maxLength: Int = 200,
        onResult: (String?) -> Unit,
    ) {
        current = DialogSpec.Prompt(
            title, label, placeholder, initial, okText, inputType, maxLength, onResult,
        )
    }

    internal fun dismiss() {
        current = null
    }
}

/** 创建并记住一个 [DialogHost]（与页面同生命周期） */
@Composable
fun rememberDialogHost(): DialogHost = remember { DialogHost() }

/** 渲染当前对话框。放在页面根部调用一次即可。 */
@Composable
fun DialogHostView(host: DialogHost) {
    when (val spec = host.current) {
        null -> Unit

        is DialogSpec.Confirm -> {
            AlertDialog(
                onDismissRequest = {
                    host.dismiss()
                    spec.onResult(false)   // 点遮罩关闭视为取消（与网页版一致）
                },
                title = { Text(spec.title, style = MaterialTheme.typography.titleMedium) },
                text = {
                    Text(spec.message, style = MaterialTheme.typography.bodyMedium)
                },
                confirmButton = {
                    TextButton(onClick = {
                        host.dismiss()
                        spec.onResult(true)
                    }) {
                        Text(
                            spec.okText,
                            color = if (spec.danger) TaTheme.colors.err else TaTheme.colors.primary,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        host.dismiss()
                        spec.onResult(false)
                    }) { Text("取消") }
                },
            )
        }

        is DialogSpec.Prompt -> {
            var text by remember(spec) { mutableStateOf(spec.initial) }
            AlertDialog(
                onDismissRequest = {
                    host.dismiss()
                    spec.onResult(null)
                },
                title = { Text(spec.title, style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { v -> text = if (v.length <= spec.maxLength) v else v.take(spec.maxLength) },
                            label = { if (spec.label.isNotBlank()) Text(spec.label) },
                            placeholder = { if (spec.placeholder.isNotBlank()) Text(spec.placeholder) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = spec.inputType),
                            visualTransformation = if (spec.inputType == KeyboardType.Password) {
                                PasswordVisualTransformation()
                            } else {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        host.dismiss()
                        spec.onResult(text.trim())
                    }) { Text(spec.okText, color = TaTheme.colors.primary) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        host.dismiss()
                        spec.onResult(null)
                    }) { Text("取消") }
                },
            )
        }
    }
}
