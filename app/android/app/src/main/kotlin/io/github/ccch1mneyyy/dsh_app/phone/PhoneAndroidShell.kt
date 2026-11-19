package io.github.ccch1mneyyy.dsh_app.phone

/** The native backend is selected only by App settings, never by model arguments. */
fun interface PhoneShellExecutor {
    fun execute(ownerId: Long, command: String, timeoutMs: Long, stillWanted: () -> Boolean,
                confirm: (String, Int) -> Decision): Map<String, Any?>
}

/** A separate grant: UI app/task waivers deliberately have no effect here. */
object PhoneAndroidShell {
    /** 按 3 分钟空闲窗口确认（默认，上游行为）。 */
    const val POLICY_WINDOW = "window"
    /** 同一个会话只确认一次，会话多久都一样。 */
    const val POLICY_SESSION = "session"
    /** 始终自动确认，从不弹窗。 */
    const val POLICY_ALWAYS = "always"

    fun normalizePolicy(value: String?): String = when (value) {
        POLICY_SESSION, POLICY_ALWAYS -> value
        else -> POLICY_WINDOW
    }

    /** Insertion-ordered LRU: a session grant outlives PhoneTasks.endLocked but not the process. */
    private val sessionGrants = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_SESSION_GRANTS
    }

    fun sessionGranted(sessionId: String?): Boolean = sessionId != null && synchronized(sessionGrants) {
        sessionGrants.containsKey(sessionId)
    }

    fun grantSession(sessionId: String?) {
        if (sessionId == null) return
        synchronized(sessionGrants) { sessionGrants[sessionId] = true }
    }

    /** Test seam: forget every remembered conversation. */
    fun clearSessionGrants() = synchronized(sessionGrants) { sessionGrants.clear() }

    private const val MAX_SESSION_GRANTS = 64

    const val MAX_COMMAND_BYTES = 2048
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 60_000L

    /** 本轮允许 key for shell commands (they have no target app). */
    const val GRANT = "android-shell"
    data class Request(val command: String, val timeoutMs: Long)

    fun parse(args: Args): Request? {
        if (!args.onlyKeys("command", "timeout_ms")) return null
        val command = args.strictText("command") ?: return null
        if (!validCommand(command)) return null
        val timeout = if (args.provided("timeout_ms")) args.strictInt("timeout_ms")?.toLong() ?: return null
            else DEFAULT_TIMEOUT_MS
        if (timeout !in 100L..MAX_TIMEOUT_MS) return null
        return Request(command, timeout)
    }

    fun validCommand(command: String): Boolean = command.isNotBlank() &&
        command.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES && command.none {
            (it.code < 32 && it !in "\n\r\t") || it.code in 0x7f..0x9f ||
                it.code == 0x061c || it.code in 0x200e..0x200f || it.code in 0x202a..0x202e || it.code in 0x2066..0x2069
        }

    fun failure(code: String): Map<String, Any?> = linkedMapOf(
        "started" to false, "backend" to null, "uid" to null, "exitCode" to null,
        "stdout" to "", "stderr" to "", "timedOut" to false, "cancelled" to (code == "cancelled"),
        "truncated" to false, "errorCode" to code,
    )

    /** Shell grants are per command; unavailable UI is not a user denial. */
    fun confirmationError(decision: Decision): String? = when (decision) {
        Decision.ALLOW_ONCE, Decision.ALLOW_APP_FOR_TASK -> null
        Decision.UNAVAILABLE -> "confirmationUnavailable"
        Decision.STOP -> "cancelled"
        else -> "confirmationDenied"
    }

    fun run(request: Request, owner: PhoneTasks.Owner, tasks: PhoneTasks, scope: SerialWorker.Scope,
            executor: PhoneShellExecutor?, enabled: () -> Boolean, locked: () -> Boolean,
            gate: () -> UserGate?, ui: PhoneUiText,
            confirmPolicy: String = POLICY_WINDOW,
            sessionId: String? = null): Map<String, Any?> {
        val policy = normalizePolicy(confirmPolicy)
        fun live() = tasks.isCurrent(owner) && !tasks.isPaused && enabled() && !locked() &&
            !Thread.currentThread().isInterrupted
        // 通道未开启 / 锁屏 / 本轮已结束 refuse this one command; they never end the turn.
        if (!enabled()) return failure("disabled")
        if (locked()) return failure("locked")
        if (!tasks.isCurrent(owner) || tasks.isPaused || Thread.currentThread().isInterrupted) return failure("stopped")
        if (executor == null) return failure("unsupported")
        return executor.execute(owner.taskId, request.command, request.timeoutMs, ::live) { backend, uid ->
            if (!live()) Decision.STOP
            else if (!(backend == "adb" && uid == 2000 ||
                backend == "shizuku" && uid in setOf(0, 2000) ||
                backend == "root" && uid == 0)) Decision.DENY
            else if (policy == POLICY_ALWAYS) Decision.ALLOW_ONCE
            else if (policy == POLICY_SESSION && sessionGranted(sessionId)) Decision.ALLOW_ONCE
            else if (policy == POLICY_WINDOW && tasks.hasGrant(null, GRANT)) Decision.ALLOW_ONCE
            else {
                val prompt = ConfirmRequest(
                    ui.get("phone_shell_confirm",
                        "执行 Android 系统命令（%1\$s，UID %2\$s）。这可能修改系统或应用数据；屏幕操作的免确认不适用：\n\n%3\$s",
                        backend, uid.toString(), request.command), // l10n:keep test default
                    appLabel = null, packageName = null, firstInApp = false, owner = owner,
                )
                val decision = scope.userWait { if (live()) gate()?.confirm(prompt) ?: Decision.UNAVAILABLE else Decision.STOP }
                if (decision == Decision.ALLOW_APP_FOR_TASK) tasks.confirmIfCurrent(owner, grant = PhoneTasks.grantKey(null, GRANT))
                // 会话档：这一次点过「允许一次」也算整个会话放行，否则等于没生效。
                if (policy == POLICY_SESSION && (decision == Decision.ALLOW_ONCE || decision == Decision.ALLOW_APP_FOR_TASK)) {
                    grantSession(sessionId)
                }
                if (live()) decision else Decision.STOP
            }
        }
    }
}
