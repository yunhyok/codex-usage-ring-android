package io.github.yunhyok.usagering.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
            // persisted. A process restart requires the user to begin again.
            "waiting" -> LoginState.Failed("PROCESS_RESTARTED")
            "authenticated" -> LoginState.Authenticated
            "failed" -> LoginState.Failed(persisted.errorCode ?: "NATIVE_ERROR")
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

    private fun safeCode(code: String): String = code.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(64).ifBlank { "NATIVE_ERROR" }

    private companion object { const val TIMEOUT_MILLIS = 15 * 60 * 1000L }
}

private class SharedPrefsLoginStateStore(context: Context) : LoginStateStore {
    private val preferences = context.getSharedPreferences("codex_login_state", Context.MODE_PRIVATE)
    override fun load(): PersistedLoginState? = preferences.getString("status", null)?.let {
        PersistedLoginState(it, preferences.getLong("started_at", 0L), preferences.getLong("expires_at", 0L), preferences.getString("error_code", null))
    }
    override fun save(value: PersistedLoginState) {
        preferences.edit().clear().putString("status", value.status).putLong("started_at", value.startedAt).putLong("expires_at", value.expiresAt).apply {
            value.errorCode?.let { putString("error_code", it) }
        }.apply()
    }
}
