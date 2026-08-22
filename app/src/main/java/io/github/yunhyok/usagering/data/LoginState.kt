package io.github.yunhyok.usagering.data

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PersistedLoginState(val status: String, val startedAt: Long = 0L, val expiresAt: Long = 0L, val errorCode: String? = null)
interface LoginStateStore {
    fun load(): PersistedLoginState?
    fun save(value: PersistedLoginState)
}

sealed interface LoginState {
    data object SignedOut : LoginState
    data class WaitingForApproval(
        val verificationUri: String,
        val userCode: String,
        val startedAtEpochMillis: Long,
        val expiresAtEpochMillis: Long,
    ) : LoginState
    data object Authenticated : LoginState
    data class Failed(val code: String) : LoginState
}

/** Coordinates device-code login without ever storing or returning credentials. */
class DeviceCodeLoginController(
    private val store: LoginStateStore,
    private val bridge: NativeCodexBridge,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context, bridge: NativeCodexBridge, now: () -> Long = { System.currentTimeMillis() }) :
        this(SharedPrefsLoginStateStore(context.applicationContext), bridge, now)
    private val _state = MutableStateFlow(restore())
    val stateFlow: StateFlow<LoginState> = _state
    val state: LoginState get() = _state.value

    @Synchronized
    fun start(): LoginState {
        if (state is LoginState.WaitingForApproval) return poll()
        // A failed poll can leave the native controller holding an active
        // login id. Reset it before retrying so the next device-code request
        // cannot be rejected as LOGIN_IN_PROGRESS.
        if (state is LoginState.Failed) bridge.shutdown()
        val result = bridge.beginDeviceLogin()
        _state.value = result.fold(
            onSuccess = { challenge ->
                val started = now()
                val expires = challenge.expiresAtEpochMillis?.takeIf { it > started } ?: (started + TIMEOUT_MILLIS)
                LoginState.WaitingForApproval(challenge.verificationUri, challenge.userCode, started, expires)
            },
            onFailure = { LoginState.Failed(safeCode(it.message ?: "NATIVE_ERROR")) },
        )
        persist(state)
        return state
    }

    @Synchronized
    fun poll(): LoginState {
        val waiting = state as? LoginState.WaitingForApproval ?: return state
        if (now() >= waiting.expiresAtEpochMillis) {
            bridge.shutdown()
            _state.value = LoginState.Failed("TIMEOUT")
            persist(state)
            return state
        }
        _state.value = when (val result = bridge.pollLogin()) {
            LoginPollResult.Waiting -> waiting
            LoginPollResult.Authenticated -> LoginState.Authenticated
            is LoginPollResult.Failed -> LoginState.Failed(safeCode(result.code))
        }
        persist(state)
        return state
    }

    @Synchronized
    fun cancel(): LoginState {
        bridge.shutdown()
        _state.value = LoginState.SignedOut
        persist(state)
        return state
    }

    /**
     * Aborts an in-flight login and records a local, allowlisted failure.
     * The native shutdown remains on the serialized controller path and no
     * provider error text is persisted or exposed to the UI.
     */
    @Synchronized
    fun fail(code: String): LoginState {
        bridge.shutdown()
        _state.value = LoginState.Failed(safeCode(code))
        persist(state)
        return state
    }

    @Synchronized
    fun logout(): LoginState {
        val result = bridge.logout()
        if (!result.ok) {
            _state.value = LoginState.Failed("LOGOUT_FAILED")
            persist(state)
            return state
        }
        bridge.shutdown()
        _state.value = LoginState.SignedOut
        persist(state)
        return state
    }

    private fun restore(): LoginState {
        val persisted = store.load() ?: return LoginState.SignedOut
        val status = persisted.status
        return when (status) {
            // Device-code challenges are one-time UI data and are intentionally not
            // persisted. A process restart requires the user to begin again. Persist
            // the terminal state synchronously so the on-disk state cannot continue
            // to advertise a challenge that no longer exists in this process.
            "waiting" -> LoginState.Failed("PROCESS_RESTARTED").also { persist(it) }
            "authenticated" -> LoginState.Authenticated
            "failed" -> {
                val code = safeCode(persisted.errorCode ?: SAFE_FALLBACK)
                LoginState.Failed(code).also {
                    // Rewrite legacy/raw persisted values, but avoid an unnecessary
                    // constructor-time write when the stored code is already safe.
                    if (persisted.errorCode != code) persist(it)
                }
            }
            else -> LoginState.SignedOut
        }
    }

    private fun persist(value: LoginState) {
        store.save(when (value) {
            LoginState.SignedOut -> PersistedLoginState("signed_out")
            LoginState.Authenticated -> PersistedLoginState("authenticated")
            is LoginState.Failed -> PersistedLoginState("failed", errorCode = safeCode(value.code))
            is LoginState.WaitingForApproval -> PersistedLoginState("waiting", value.startedAtEpochMillis, value.expiresAtEpochMillis)
        })
    }

    private fun safeCode(code: String): String = sanitizeLoginErrorCode(code)

    private companion object { const val TIMEOUT_MILLIS = 15 * 60 * 1000L }
}

enum class LoginAction { START, CANCEL, LOGOUT, LOGOUT_RETRY }

/**
 * Owns login transitions outside the Compose composition. The coordinator's
 * scope is independent of a tab/route composition, while service callbacks are
 * injected as narrow operations rather than retaining an Activity Context.
 */
class LoginOperationCoordinator(
    private val loginController: DeviceCodeLoginController,
    private val startPolling: () -> Boolean,
    private val stopPolling: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private val _action = MutableStateFlow<LoginAction?>(null)
    val actionFlow: StateFlow<LoginAction?> = _action.asStateFlow()

    fun start(): Job? = launchAction(LoginAction.START) {
        var pollingStarted = false
        try {
            val started = loginController.start()
            if (started is LoginState.WaitingForApproval) {
                pollingStarted = runCatching { startPolling() }.getOrDefault(false)
                if (!pollingStarted) loginController.fail("POLLING_SERVICE_UNAVAILABLE")
            }
        } finally {
            if (!pollingStarted) stopPollingSafely()
        }
    }

    fun cancel(): Job? = launchAction(LoginAction.CANCEL) {
        try {
            loginController.cancel()
        } finally {
            stopPollingSafely()
        }
    }

    fun logout(): Job? = launchAction(LoginAction.LOGOUT) {
        try {
            loginController.logout()
        } finally {
            stopPollingSafely()
        }
    }

    fun retryLogout(): Job? = launchAction(LoginAction.LOGOUT_RETRY) {
        try {
            loginController.logout()
        } finally {
            stopPollingSafely()
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun launchAction(action: LoginAction, operation: () -> Unit): Job? {
        synchronized(lock) {
            if (_action.value != null) return null
            _action.value = action
        }
        return scope.launch {
            try {
                operation()
            } finally {
                _action.value = null
            }
        }
    }

    private fun stopPollingSafely() {
        runCatching { stopPolling() }
    }
}

private const val SAFE_FALLBACK = "NATIVE_ERROR"

/**
 * Native device-login errors are a closed protocol. Keep this list in lockstep
 * with ErrorCode's login variants in native/src/boundary.rs; arbitrary provider
 * text must never become a persisted or UI-visible error code.
 */
private val NATIVE_LOGIN_ERROR_CODES = setOf(
    "LOGIN_IN_PROGRESS",
    "LOGIN_FAILED",
    "LOGIN_START_TRANSPORT",
    "LOGIN_START_TLS",
    "LOGIN_START_TLS_REVOKED",
    "LOGIN_START_DNS",
    "LOGIN_START_NOT_SUPPORTED",
    "LOGIN_START_SERVER",
    "LOGIN_START_HTTP_4XX",
    "LOGIN_START_HTTP_5XX",
    "LOGIN_START_RATE_LIMITED",
    "LOGIN_START_DESERIALIZE",
    "LOGIN_START_TIMEOUT",
    "LOGIN_TIMEOUT",
)

// These are local, non-secret classifications produced by the Kotlin boundary
// itself. They are not native provider detail, but must remain stable for the UI.
private val LOCAL_LOGIN_ERROR_CODES = setOf(
    SAFE_FALLBACK,
    "INVALID_JSON",
    "INVALID_RESPONSE",
    "INVALID_URI",
    "INVALID_CODE",
    "NATIVE_UNAVAILABLE",
    "POLLING_SERVICE_UNAVAILABLE",
    "TIMEOUT",
    "PROCESS_RESTARTED",
    "LOGOUT_FAILED",
)

internal fun sanitizeLoginErrorCode(code: String): String =
    if (code in NATIVE_LOGIN_ERROR_CODES || code in LOCAL_LOGIN_ERROR_CODES) code else SAFE_FALLBACK

/** UI-safe grouping for the small set of login failures that warrant guidance. */
internal enum class LoginFailureKind {
    GENERIC,
    TLS_CONNECTION,
}

internal fun classifyLoginFailure(code: String): LoginFailureKind =
    if (code == "LOGIN_START_TLS_REVOKED") LoginFailureKind.TLS_CONNECTION else LoginFailureKind.GENERIC

private class SharedPrefsLoginStateStore(context: Context) : LoginStateStore {
    private val preferences = context.getSharedPreferences("codex_login_state", Context.MODE_PRIVATE)
    override fun load(): PersistedLoginState? = preferences.getString("status", null)?.let {
        PersistedLoginState(it, preferences.getLong("started_at", 0L), preferences.getLong("expires_at", 0L), preferences.getString("error_code", null))
    }
    @SuppressLint("ApplySharedPref")
    override fun save(value: PersistedLoginState) {
        preferences.edit().clear().putString("status", value.status).putLong("started_at", value.startedAt).putLong("expires_at", value.expiresAt).apply {
            value.errorCode?.let { putString("error_code", it) }
        // Login-state transitions, especially waiting -> PROCESS_RESTARTED during
        // construction, must reach disk before the process can be killed.
        }.commit()
    }
}
