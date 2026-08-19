package com.example.myapp

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myapp.communication.EncodedParameterCommand

enum class ConnectionStatus(val displayName: String) {
    Disconnected("\u672a\u8fde\u63a5"),
    Connecting("\u8fde\u63a5\u4e2d"),
    Connected("\u5df2\u8fde\u63a5"),
    Failed("\u8fde\u63a5\u5931\u8d25")
}

/**
 * TCP 连接设置弹窗。
 *
 * 输入值和连接动作由主页面持有，弹窗只负责展示状态、校验结果并转发用户操作。
 */
@Composable
fun ConnectionSettingsDialog(
    host: String,
    port: String,
    connectionStatus: ConnectionStatus,
    errorMessage: String?,
    hostHasError: Boolean,
    portHasError: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\u8fde\u63a5\u8bbe\u7f6e") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = onHostChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("\u670d\u52a1\u5668IP\u5730\u5740") },
                    enabled = connectionStatus != ConnectionStatus.Connecting,
                    singleLine = true,
                    isError = hostHasError
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("\u7aef\u53e3") },
                    enabled = connectionStatus != ConnectionStatus.Connecting,
                    singleLine = true,
                    isError = portHasError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text(
                    text = "\u8fde\u63a5\u72b6\u6001\uff1a${connectionStatus.displayName}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("\u53d6\u6d88")
                }
                if (connectionStatus == ConnectionStatus.Connected) {
                    TextButton(onClick = onDisconnect) {
                        Text("\u65ad\u5f00\u8fde\u63a5")
                    }
                }
                TextButton(
                    onClick = onConnect,
                    enabled = connectionStatus != ConnectionStatus.Connecting
                ) {
                    Text("\u8fde\u63a5")
                }
            }
        }
    )
}

/**
 * 手动参数发送前的确认弹窗。
 * 发送期间禁止关闭和重复确认，避免同一份参数快照被重复写入 TCP 连接。
 */
@Composable
fun SendConfirmationDialog(
    endpointLabel: String,
    command: EncodedParameterCommand,
    isSending: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!isSending) {
                onCancel()
            }
        },
        title = { Text("\u786e\u8ba4\u53d1\u9001") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("\u64cd\u4f5c\uff1a${command.operation.displayName}")
                Text("\u8fde\u63a5\u7aef\u70b9\uff1a$endpointLabel")
                Text(
                    text = "\u547d\u4ee4\u5185\u5bb9",
                    style = MaterialTheme.typography.labelLarge
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = command.prettyJson,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !isSending
            ) {
                Text("\u53d6\u6d88")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isSending
            ) {
                Text("\u786e\u8ba4\u53d1\u9001")
            }
        }
    )
}
