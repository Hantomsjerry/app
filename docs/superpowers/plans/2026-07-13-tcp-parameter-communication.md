# TCP Parameter Communication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent TCP client, editable connection settings, newline-delimited JSON parameter commands, and a user confirmation dialog before every send.

**Architecture:** Keep parameter serialization and socket I/O in focused Kotlin files that do not depend on Compose. The existing Compose screen owns visible state and launches suspend network calls away from the main thread; dialogs collect the endpoint and show the exact pending JSON before it is sent.

**Tech Stack:** Kotlin, Android Jetpack Compose, Material 3, `org.json`, Kotlin coroutines, Java `Socket`, JUnit 4.

## Global Constraints

- The Android app is the TCP client and keeps one persistent socket while the screen is active.
- Every outgoing UTF-8 JSON object ends with exactly one newline byte (`\n`).
- NetAssist returns `success\n`; trimmed case-insensitive `success` means success.
- Footer operations use `save_parameters`, `apply_parameters`, and `sync_to_device`.
- Each footer action opens a confirmation dialog showing the endpoint and exact pretty-printed JSON before sending.
- Cancel never writes to the socket; only one send may be active at a time.
- Do not automatically retry commands.
- Persist only the last host and port with `SharedPreferences`.
- The project must compile and run from Android Studio; no APK install or packaging handoff is required.
- The top header continues to omit the back arrow.
- Git commit steps are omitted because the `git` executable is unavailable in this workspace.

---

### Task 1: Parameter Command Model and JSON Codec

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/example/myapp/communication/ParameterCommand.kt`
- Create: `app/src/test/java/com/example/myapp/communication/ParameterCommandCodecTest.kt`

**Interfaces:**
- Consumes: Current parameter values from `DetectionParametersScreen`.
- Produces: `DetectionParameters`, `ParameterOperation`, `EncodedParameterCommand`, and `ParameterCommandCodec.encode(...)` / `isSuccessResponse(...)`.

- [ ] **Step 1: Add test-only JSON and coroutine dependencies**

Add the following dependencies to `app/build.gradle.kts`:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
testImplementation("org.json:json:20250517")
```

The Android platform supplies `org.json` in production; the explicit test dependency supplies the JVM implementation for local unit tests.

- [ ] **Step 2: Write failing codec tests**

Create `ParameterCommandCodecTest.kt`:

```kotlin
package com.example.myapp.communication

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterCommandCodecTest {
    private val parameters = DetectionParameters(
        lv1Sensitivity = 30,
        lv1Strength = 100,
        lv1Density = 0,
        enhancedInference = false,
        lv1AreaMask = true,
        minArea = 5000,
        template = "400mmBase.engine",
        lv2Strength = 100,
        lv3Strength = 60.0,
        actionDuration = 1200,
        rejectDelay = 700
    )

    @Test
    fun encodeContainsOperationTimestampAndEveryParameter() {
        val encoded = ParameterCommandCodec.encode(
            operation = ParameterOperation.Apply,
            timestamp = 1783745905766,
            parameters = parameters
        )

        val root = JSONObject(encoded.compactJson)
        val values = root.getJSONObject("parameters")
        assertEquals("apply_parameters", root.getString("type"))
        assertEquals(1783745905766, root.getLong("timestamp"))
        assertEquals(30, values.getInt("lv1Sensitivity"))
        assertEquals(100, values.getInt("lv1Strength"))
        assertEquals(0, values.getInt("lv1Density"))
        assertFalse(values.getBoolean("enhancedInference"))
        assertTrue(values.getBoolean("lv1AreaMask"))
        assertEquals(5000, values.getInt("minArea"))
        assertEquals("400mmBase.engine", values.getString("template"))
        assertEquals(100, values.getInt("lv2Strength"))
        assertEquals(60.0, values.getDouble("lv3Strength"), 0.0)
        assertEquals(1200, values.getInt("actionDuration"))
        assertEquals(700, values.getInt("rejectDelay"))
    }

    @Test
    fun operationNamesMatchFooterActions() {
        assertEquals("save_parameters", ParameterOperation.Save.wireName)
        assertEquals("apply_parameters", ParameterOperation.Apply.wireName)
        assertEquals("sync_to_device", ParameterOperation.Sync.wireName)
    }

    @Test
    fun wireTextIsCompactJsonFollowedByExactlyOneNewline() {
        val encoded = ParameterCommandCodec.encode(ParameterOperation.Save, 1, parameters)
        assertEquals(encoded.compactJson + "\n", encoded.wireText)
        assertFalse(encoded.compactJson.contains('\n'))
        assertTrue(encoded.prettyJson.contains('\n'))
    }

    @Test
    fun responseParserAcceptsOnlyTrimmedCaseInsensitiveSuccess() {
        assertTrue(ParameterCommandCodec.isSuccessResponse(" success "))
        assertTrue(ParameterCommandCodec.isSuccessResponse("SUCCESS"))
        assertFalse(ParameterCommandCodec.isSuccessResponse("failed"))
        assertFalse(ParameterCommandCodec.isSuccessResponse(""))
    }
}
```

- [ ] **Step 3: Run the codec test and verify RED**

Run:

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.communication.ParameterCommandCodecTest"
```

Expected: compilation fails because `DetectionParameters` and `ParameterCommandCodec` do not exist.

- [ ] **Step 4: Implement the command model and codec**

Create `ParameterCommand.kt` with these exact public types:

```kotlin
package com.example.myapp.communication

import org.json.JSONObject

data class DetectionParameters(
    val lv1Sensitivity: Int,
    val lv1Strength: Int,
    val lv1Density: Int,
    val enhancedInference: Boolean,
    val lv1AreaMask: Boolean,
    val minArea: Int,
    val template: String,
    val lv2Strength: Int,
    val lv3Strength: Double,
    val actionDuration: Int,
    val rejectDelay: Int
)

enum class ParameterOperation(val wireName: String, val displayName: String) {
    Save("save_parameters", "保存参数"),
    Apply("apply_parameters", "应用参数"),
    Sync("sync_to_device", "同步到设备")
}

data class EncodedParameterCommand(
    val operation: ParameterOperation,
    val timestamp: Long,
    val prettyJson: String,
    val compactJson: String,
    val wireText: String
)

object ParameterCommandCodec {
    fun encode(
        operation: ParameterOperation,
        timestamp: Long,
        parameters: DetectionParameters
    ): EncodedParameterCommand {
        val values = JSONObject()
            .put("lv1Sensitivity", parameters.lv1Sensitivity)
            .put("lv1Strength", parameters.lv1Strength)
            .put("lv1Density", parameters.lv1Density)
            .put("enhancedInference", parameters.enhancedInference)
            .put("lv1AreaMask", parameters.lv1AreaMask)
            .put("minArea", parameters.minArea)
            .put("template", parameters.template)
            .put("lv2Strength", parameters.lv2Strength)
            .put("lv3Strength", parameters.lv3Strength)
            .put("actionDuration", parameters.actionDuration)
            .put("rejectDelay", parameters.rejectDelay)
        val root = JSONObject()
            .put("type", operation.wireName)
            .put("timestamp", timestamp)
            .put("parameters", values)
        val compact = root.toString()
        return EncodedParameterCommand(operation, timestamp, root.toString(2), compact, "$compact\n")
    }

    fun isSuccessResponse(response: String): Boolean =
        response.trim().equals("success", ignoreCase = true)
}
```

- [ ] **Step 5: Run the codec test and verify GREEN**

Run the command from Step 3. Expected: all four codec tests pass.

---

### Task 2: Endpoint Validation and Local Storage

**Files:**
- Create: `app/src/main/java/com/example/myapp/communication/TcpEndpoint.kt`
- Create: `app/src/main/java/com/example/myapp/communication/EndpointStore.kt`
- Create: `app/src/test/java/com/example/myapp/communication/TcpEndpointTest.kt`

**Interfaces:**
- Consumes: Host and port text entered in the connection dialog.
- Produces: `TcpEndpoint`, `validateTcpEndpoint(host, portText)`, and `EndpointStore.load()` / `save(endpoint)`.

- [ ] **Step 1: Write failing endpoint validation tests**

```kotlin
package com.example.myapp.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpEndpointTest {
    @Test
    fun validHostAndPortCreateEndpoint() {
        val result = validateTcpEndpoint(" 192.168.1.20 ", "9000")
        assertEquals(TcpEndpoint("192.168.1.20", 9000), result.getOrThrow())
    }

    @Test
    fun emptyHostIsRejected() {
        assertTrue(validateTcpEndpoint(" ", "9000").isFailure)
    }

    @Test
    fun nonNumericAndOutOfRangePortsAreRejected() {
        assertTrue(validateTcpEndpoint("localhost", "abc").isFailure)
        assertTrue(validateTcpEndpoint("localhost", "0").isFailure)
        assertTrue(validateTcpEndpoint("localhost", "65536").isFailure)
    }
}
```

- [ ] **Step 2: Run the endpoint test and verify RED**

Run:

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.communication.TcpEndpointTest"
```

Expected: compilation fails because `TcpEndpoint` and `validateTcpEndpoint` do not exist.

- [ ] **Step 3: Implement endpoint validation**

Create `TcpEndpoint.kt`:

```kotlin
package com.example.myapp.communication

data class TcpEndpoint(val host: String, val port: Int) {
    val label: String get() = "$host:$port"
}

fun validateTcpEndpoint(host: String, portText: String): Result<TcpEndpoint> {
    val normalizedHost = host.trim()
    if (normalizedHost.isEmpty()) return Result.failure(IllegalArgumentException("请输入服务器IP地址"))
    val port = portText.trim().toIntOrNull()
        ?: return Result.failure(IllegalArgumentException("端口必须是数字"))
    if (port !in 1..65535) return Result.failure(IllegalArgumentException("端口范围必须是1到65535"))
    return Result.success(TcpEndpoint(normalizedHost, port))
}
```

- [ ] **Step 4: Run the endpoint test and verify GREEN**

Run the command from Step 2. Expected: all three endpoint tests pass.

- [ ] **Step 5: Implement SharedPreferences endpoint storage**

Create `EndpointStore.kt`:

```kotlin
package com.example.myapp.communication

import android.content.Context

class EndpointStore(context: Context) {
    private val preferences = context.getSharedPreferences("tcp_connection", Context.MODE_PRIVATE)

    fun load(): TcpEndpoint? {
        val host = preferences.getString("host", null) ?: return null
        val port = preferences.getInt("port", -1)
        return if (host.isNotBlank() && port in 1..65535) TcpEndpoint(host, port) else null
    }

    fun save(endpoint: TcpEndpoint) {
        preferences.edit()
            .putString("host", endpoint.host)
            .putInt("port", endpoint.port)
            .apply()
    }
}
```

This class contains only Android persistence plumbing; endpoint validity remains covered by the pure JVM tests.

---

### Task 3: TCP Client and Newline Protocol

**Files:**
- Create: `app/src/main/java/com/example/myapp/communication/TcpParameterClient.kt`
- Create: `app/src/test/java/com/example/myapp/communication/TcpParameterClientTest.kt`

**Interfaces:**
- Consumes: A validated `TcpEndpoint` and `EncodedParameterCommand.wireText`.
- Produces: `connect(endpoint)`, `sendAndReceive(wireText)`, `isConnected`, and `close()`.

- [ ] **Step 1: Write a failing local-server integration test**

```kotlin
package com.example.myapp.communication

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpParameterClientTest {
    @Test
    fun sendsOneJsonLineAndReadsSuccessLine() = runBlocking {
        val server = ServerSocket(0)
        val received = arrayOfNulls<String>(1)
        val finished = CountDownLatch(1)
        val serverThread = Thread {
            server.accept().use { socket ->
                received[0] = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                socket.getOutputStream().bufferedWriter(Charsets.UTF_8).apply {
                    write("success\n")
                    flush()
                }
            }
            finished.countDown()
        }.apply { start() }

        val client = TcpParameterClient(connectTimeoutMillis = 2000, readTimeoutMillis = 2000)
        try {
            client.connect(TcpEndpoint("127.0.0.1", server.localPort))
            assertTrue(client.isConnected)
            val response = client.sendAndReceive("{\"type\":\"apply_parameters\"}\n")
            assertEquals("success", response)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals("{\"type\":\"apply_parameters\"}", received[0])
        } finally {
            client.close()
            server.close()
            serverThread.join(2000)
        }
        assertFalse(client.isConnected)
    }

    @Test(expected = IllegalStateException::class)
    fun sendingWithoutConnectionFails() = runBlocking {
        TcpParameterClient().sendAndReceive("{}\n")
    }
}
```

- [ ] **Step 2: Run the TCP client test and verify RED**

Run:

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.communication.TcpParameterClientTest"
```

Expected: compilation fails because `TcpParameterClient` does not exist.

- [ ] **Step 3: Implement the persistent TCP client**

Create `TcpParameterClient.kt`:

```kotlin
package com.example.myapp.communication

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TcpParameterClient(
    private val connectTimeoutMillis: Int = 5000,
    private val readTimeoutMillis: Int = 5000
) : Closeable {
    private val mutex = Mutex()
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    suspend fun connect(endpoint: TcpEndpoint) = mutex.withLock {
        withContext(Dispatchers.IO) {
            closeResources()
            val newSocket = Socket()
            try {
                newSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMillis)
                newSocket.soTimeout = readTimeoutMillis
                socket = newSocket
                reader = newSocket.getInputStream().bufferedReader(Charsets.UTF_8)
                writer = newSocket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            } catch (error: Exception) {
                newSocket.close()
                closeResources()
                throw error
            }
        }
    }

    suspend fun sendAndReceive(wireText: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(isConnected) { "TCP客户端尚未连接" }
            try {
                writer?.apply {
                    write(wireText)
                    flush()
                } ?: error("TCP输出流不可用")
                reader?.readLine() ?: throw EOFException("服务器已断开连接")
            } catch (error: Exception) {
                closeResources()
                throw error
            }
        }
    }

    override fun close() {
        closeResources()
    }

    private fun closeResources() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }
}
```

- [ ] **Step 4: Run the TCP client test and verify GREEN**

Run the command from Step 2. Expected: both TCP client tests pass.

---

### Task 4: Connection and Send Confirmation Dialogs

**Files:**
- Create: `app/src/main/java/com/example/myapp/CommunicationDialogs.kt`
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt`

**Interfaces:**
- Consumes: Endpoint input, connection state, and `EncodedParameterCommand`.
- Produces: `ConnectionSettingsDialog(...)` and `SendConfirmationDialog(...)` composables.

- [ ] **Step 1: Add focused dialog composables**

Create `CommunicationDialogs.kt` with these signatures:

```kotlin
@Composable
fun ConnectionSettingsDialog(
    host: String,
    port: String,
    connectionStatus: ConnectionStatus,
    errorMessage: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
)

@Composable
fun SendConfirmationDialog(
    endpointLabel: String,
    command: EncodedParameterCommand,
    isSending: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
)
```

Define the UI state in the same file:

```kotlin
enum class ConnectionStatus(val displayName: String) {
    Disconnected("未连接"),
    Connecting("连接中"),
    Connected("已连接"),
    Failed("连接失败")
}
```

`ConnectionSettingsDialog` uses two `OutlinedTextField` controls; the port field uses `KeyboardType.Number`. It disables Connect while `Connecting`, shows Disconnect only when `Connected`, and renders `errorMessage` in the Material error color.

`SendConfirmationDialog` shows `command.operation.displayName`, the endpoint, and `command.prettyJson` in a vertically scrollable monospace-style text area. Its confirm action reads `确认发送`, is disabled while `isSending`, and its cancel action reads `取消`.

- [ ] **Step 2: Add Internet permission**

Add this direct child of `<manifest>` in `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 3: Wire persistent communication state into the screen**

In `DetectionParametersScreen`, create and retain:

```kotlin
val context = LocalContext.current
val scope = rememberCoroutineScope()
val client = remember { TcpParameterClient() }
val endpointStore = remember { EndpointStore(context.applicationContext) }
val storedEndpoint = remember { endpointStore.load() }

var connectionStatus by rememberSaveable { mutableStateOf(ConnectionStatus.Disconnected) }
var hostText by rememberSaveable { mutableStateOf(storedEndpoint?.host.orEmpty()) }
var portText by rememberSaveable { mutableStateOf(storedEndpoint?.port?.toString().orEmpty()) }
var activeEndpoint by remember { mutableStateOf<TcpEndpoint?>(null) }
var showConnectionDialog by rememberSaveable { mutableStateOf(false) }
var connectionError by rememberSaveable { mutableStateOf<String?>(null) }
var pendingCommand by remember { mutableStateOf<EncodedParameterCommand?>(null) }
var isSending by rememberSaveable { mutableStateOf(false) }

DisposableEffect(client) {
    onDispose { client.close() }
}
```

Use `scope.launch` for connect/send actions. `TcpParameterClient` moves socket work to `Dispatchers.IO`; Compose state changes occur before and after the suspend calls on the main dispatcher.

- [ ] **Step 4: Make the header status open connection settings**

Change the header interface to:

```kotlin
@Composable
private fun TopStatusHeader(
    connectionStatus: ConnectionStatus,
    onConnectionClick: () -> Unit
)
```

Pass `connectionStatus.displayName` to the first status pill and make that pill clickable. Keep the centered title and the no-back-arrow layout unchanged.

- [ ] **Step 5: Implement connect and disconnect actions**

On Connect:

```kotlin
val endpoint = validateTcpEndpoint(hostText, portText).getOrElse {
    connectionError = it.message
    return@ConnectionSettingsDialog
}
scope.launch {
    connectionStatus = ConnectionStatus.Connecting
    connectionError = null
    runCatching { client.connect(endpoint) }
        .onSuccess {
            activeEndpoint = endpoint
            endpointStore.save(endpoint)
            connectionStatus = ConnectionStatus.Connected
            showConnectionDialog = false
        }
        .onFailure {
            activeEndpoint = null
            connectionStatus = ConnectionStatus.Failed
            connectionError = it.message ?: "连接服务器失败"
        }
}
```

On Disconnect, call `client.close()`, clear `activeEndpoint`, set status to `Disconnected`, and clear the dialog error.

---

### Task 5: Footer Actions, Exact Preview, and Result Feedback

**Files:**
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt`

**Interfaces:**
- Consumes: Current Compose parameter state, active endpoint, codec, and TCP client.
- Produces: Confirm/cancel send flow for all three footer operations and visible result feedback.

- [ ] **Step 1: Build a snapshot from current UI values**

Inside `DetectionParametersScreen`, add a local snapshot builder or equivalent direct construction:

```kotlin
fun currentParameters() = DetectionParameters(
    lv1Sensitivity = lv1Sensitivity.roundToInt(),
    lv1Strength = lv1Strength.roundToInt(),
    lv1Density = lv1Density.roundToInt(),
    enhancedInference = enhancedInference,
    lv1AreaMask = lv1AreaMask,
    minArea = minArea.roundToInt(),
    template = "400mmBase.engine",
    lv2Strength = lv2Strength.roundToInt(),
    lv3Strength = lv3Strength.toDouble(),
    actionDuration = actionDuration.roundToInt(),
    rejectDelay = rejectDelay.roundToInt()
)
```

- [ ] **Step 2: Replace footer no-op handlers with operation callbacks**

Change `BottomActionBar` to accept:

```kotlin
@Composable
private fun BottomActionBar(
    onSave: () -> Unit,
    onApply: () -> Unit,
    onSync: () -> Unit
)
```

Make each button invoke its matching callback. At the screen level, one `prepareSend(operation)` handler must:

```kotlin
val endpoint = activeEndpoint
if (connectionStatus != ConnectionStatus.Connected || endpoint == null) {
    Toast.makeText(context, "请先连接服务器", Toast.LENGTH_SHORT).show()
    showConnectionDialog = true
    return
}
pendingCommand = ParameterCommandCodec.encode(
    operation = operation,
    timestamp = System.currentTimeMillis(),
    parameters = currentParameters()
)
```

Map Save, Apply, and Sync to their exact enum values. The timestamp and JSON are created once here so the dialog and socket use the same command.

- [ ] **Step 3: Render the exact pending JSON and cancel safely**

When `pendingCommand != null`, render `SendConfirmationDialog`. The cancel callback sets `pendingCommand = null` only when `isSending` is false. No socket method is called on cancel.

- [ ] **Step 4: Send after confirmation and process the response**

The confirm callback captures the current pending command and launches:

```kotlin
scope.launch {
    isSending = true
    runCatching { client.sendAndReceive(command.wireText) }
        .onSuccess { response ->
            if (ParameterCommandCodec.isSuccessResponse(response)) {
                Toast.makeText(context, "${command.operation.displayName}成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "服务器返回：$response", Toast.LENGTH_LONG).show()
            }
        }
        .onFailure { error ->
            client.close()
            activeEndpoint = null
            connectionStatus = ConnectionStatus.Disconnected
            Toast.makeText(context, error.message ?: "发送失败，请重新连接", Toast.LENGTH_LONG).show()
        }
    isSending = false
    pendingCommand = null
}
```

Do not retry on failure. Disable the confirm action while waiting for the response.

- [ ] **Step 5: Run all local unit tests**

Run:

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` and all existing plus new unit tests pass.

- [ ] **Step 6: Compile the debug app for Android Studio**

Run:

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. This is a compile verification only; do not install the APK.

- [ ] **Step 7: Manual Android Studio / NetAssist verification**

Run the app from Android Studio, open the top connection status, and connect to a NetAssist TCP server. Verify these observable behaviors:

1. Invalid host/port stays in the dialog and shows a Chinese validation error.
2. A successful connection changes the top status to `已连接`.
3. Each footer button shows its matching type and all current values before sending.
4. Cancel produces no NetAssist data.
5. Confirm produces one compact JSON line followed by `\n`.
6. Sending `success\n` from NetAssist displays the matching success message.
7. Closing NetAssist causes the next send to report a disconnect and resets the app status.
