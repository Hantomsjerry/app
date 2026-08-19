package com.example.myapp.communication

/** 已通过校验的 TCP 服务端地址。 */
data class TcpEndpoint(val host: String, val port: Int) {
    val label: String
        get() = "$host:$port"
}

/** 规范化用户输入，并确保主机名非空且端口位于 TCP 合法范围内。 */
fun validateTcpEndpoint(host: String, portText: String): Result<TcpEndpoint> {
    val normalizedHost = host.trim()
    if (normalizedHost.isEmpty()) {
        return Result.failure(IllegalArgumentException("\u8bf7\u8f93\u5165\u670d\u52a1\u5668IP\u5730\u5740"))
    }

    val normalizedPortText = portText.trim()
    val port = normalizedPortText.toLongOrNull()
    if (port == null) {
        val message = if (normalizedPortText.matches(Regex("[+-]?\\d+"))) {
            "\u7aef\u53e3\u8303\u56f4\u5fc5\u987b\u662f1\u523065535"
        } else {
            "\u7aef\u53e3\u5fc5\u987b\u662f\u6570\u5b57"
        }
        return Result.failure(IllegalArgumentException(message))
    }
    if (port !in 1L..65535L) {
        return Result.failure(IllegalArgumentException("\u7aef\u53e3\u8303\u56f4\u5fc5\u987b\u662f1\u523065535"))
    }

    return Result.success(TcpEndpoint(normalizedHost, port.toInt()))
}
