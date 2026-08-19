package com.example.myapp.communication

import android.content.Context

/** 使用 SharedPreferences 保存最后一次成功连接的端点，供下次启动回填。 */
class EndpointStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): TcpEndpoint? {
        if (!preferences.contains(HOST_KEY) || !preferences.contains(PORT_KEY)) {
            return null
        }

        // 旧版本或外部修改可能留下错误类型，因此读取后仍要走统一校验。
        return try {
            val host = preferences.getString(HOST_KEY, null) ?: return null
            val port = preferences.getInt(PORT_KEY, -1)
            validateTcpEndpoint(host, port.toString()).getOrNull()
        } catch (_: ClassCastException) {
            null
        }
    }

    fun save(endpoint: TcpEndpoint) {
        preferences.edit()
            .putString(HOST_KEY, endpoint.host)
            .putInt(PORT_KEY, endpoint.port)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "tcp_connection"
        const val HOST_KEY = "host"
        const val PORT_KEY = "port"
    }
}
