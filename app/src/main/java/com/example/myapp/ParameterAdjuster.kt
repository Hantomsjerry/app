package com.example.myapp

/** 按指定步长调整整数参数，并把结果限制在界面允许的闭区间内。 */
fun adjustParameterValue(
    value: Int,
    direction: Int,
    step: Int,
    range: IntRange
): Int = (value + direction * step).coerceIn(range)

/** 提交键盘输入；合法整数保留精确值，越界时限制范围，非法输入则恢复原值。 */
fun commitParameterInput(
    text: String,
    previousValue: Int,
    range: IntRange
): Int = text.toIntOrNull()?.coerceIn(range) ?: previousValue
