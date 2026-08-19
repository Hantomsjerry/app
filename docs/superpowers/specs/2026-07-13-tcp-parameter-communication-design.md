# TCP Parameter Communication Design

## Goal

Add TCP client communication to the existing detection-parameter Android app. The user configures a server endpoint, connects manually, reviews the complete outgoing parameter message, and confirms or cancels each send operation.

The project only needs to compile and run from Android Studio. Installing or separately packaging an APK is outside this change.

## Connection Model

Use one persistent TCP socket while the screen is active.

- The app acts as the TCP client.
- The user opens a connection settings dialog from the connection-status control in the top header.
- The dialog contains server IP address, server port, a connect action, and a disconnect action.
- The endpoint is stored locally and prefilled the next time the app starts.
- The visible connection states are `Disconnected`, `Connecting`, `Connected`, and `Failed`.
- Leaving the activity releases the socket and its reader/writer resources.
- Network work never runs on the Android main thread.

Automatic infinite reconnect is not included. After a failure or remote disconnect, the user can open connection settings and connect again.

## TCP Message Framing

Messages use UTF-8 newline-delimited text.

- Every outgoing JSON object is serialized to one line.
- A newline byte (`\n`) is appended after every JSON object.
- The socket is flushed after each message.
- Incoming test responses are read one line at a time.

For NetAssist testing, the success response is:

```text
success\n
```

The client treats a trimmed, case-insensitive `success` line as success. A JSON response can be supported later without changing the outgoing protocol.

## Outgoing JSON Protocol

Each footer operation uses the same envelope and parameter object. The `type` value identifies the requested operation:

- `save_parameters`
- `apply_parameters`
- `sync_to_device`

Example:

```json
{
  "type": "apply_parameters",
  "timestamp": 1783745905766,
  "parameters": {
    "lv1Sensitivity": 30,
    "lv1Strength": 100,
    "lv1Density": 0,
    "enhancedInference": false,
    "lv1AreaMask": false,
    "minArea": 5000,
    "template": "400mmBase.engine",
    "lv2Strength": 100,
    "lv3Strength": 60.0,
    "actionDuration": 1200,
    "rejectDelay": 700
  }
}
```

`timestamp` is Unix epoch time in milliseconds at confirmation time. Numeric values are emitted as JSON numbers, toggles as booleans, and the template as a string.

## Send Confirmation Flow

All three footer buttons follow the same flow.

1. If the client is not connected, show a Chinese message asking the user to connect first. Do not open the confirmation dialog.
2. Build a pending parameter message from the current UI values and create its timestamp.
3. Open a confirmation dialog showing the operation name, endpoint, and complete pretty-printed JSON.
4. `Cancel` closes the dialog without writing to the socket.
5. `Confirm send` serializes the exact pending message shown in the dialog as compact JSON, appends `\n`, writes it to the socket, and waits for one response line.
6. A `success` response shows a Chinese success message. Any other response is shown as a server error.

Only one send operation may be in progress at a time. While waiting for a response, the confirmation action is disabled to prevent duplicate commands.

## Components and Boundaries

### Parameter Model and Codec

A focused Kotlin model represents all current UI parameters. A codec converts an operation and model into compact or pretty JSON. The codec does not access Android UI or sockets, so it can be unit tested directly.

### TCP Client

A dedicated client owns the socket, buffered reader, and buffered writer. It exposes connection state and send results. It validates IP/host text and the port range before connecting, applies connection/read timeouts, serializes socket operations, and closes all resources after errors.

### Screen State

The Compose screen continues to own editable parameter values. It observes connection state, opens the settings dialog, opens the send confirmation dialog, and displays short Chinese result messages. Socket details remain outside composables.

## Error Handling

- Empty host or a port outside `1..65535`: keep the settings dialog open and show a validation message.
- Connection timeout or refusal: close partial resources and show `Connection failed` in Chinese.
- Remote socket closure: change state to disconnected and tell the user to reconnect.
- Send timeout or I/O failure: close the socket, mark disconnected, and show the error.
- Non-`success` response: keep the connection when it is still usable and display the returned text as a server-side failure.

No command is automatically retried because machine-control commands must not be duplicated without user confirmation.

## Local Persistence

Store only the last server host and port with Android `SharedPreferences`. Parameter persistence is unchanged and remains outside this feature.

## Testing and Verification

Unit tests cover:

- The exact operation type for each footer action.
- Every current parameter in generated JSON.
- JSON number, boolean, and string types.
- Pretty JSON for the confirmation dialog and compact newline-terminated JSON for TCP.
- Parsing `success` with surrounding whitespace and letter-case differences.
- Rejecting non-success response text.
- Host and port validation.

Final verification runs the project unit tests and a debug compilation so the project is ready to launch from Android Studio. No APK installation step is required.
