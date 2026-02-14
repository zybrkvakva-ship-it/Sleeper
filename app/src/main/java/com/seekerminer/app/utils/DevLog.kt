package com.seekerminer.app.utils

import android.util.Log
import com.seekerminer.app.BuildConfig

/**
 * Централизованное логирование для разработки и отладки.
 * В release (BuildConfig.DEBUG == false) логи можно отключить или понизить уровень.
 * Фильтр в logcat: по TAG (MiningViewModel, WalletManager, MiningBackendApi и т.д.) или по "SeekerMiner".
 */
object DevLog {

    private const val GLOBAL_PREFIX = "[SeekerMiner]"

    /** Включить ли подробные логи. true = логируем всё, false = только ошибки (если нужно в release). */
    private val verboseEnabled: Boolean
        get() = BuildConfig.DEBUG

    fun d(tag: String, msg: String) {
        if (verboseEnabled) Log.d(tag, "$GLOBAL_PREFIX $msg")
    }

    fun d(tag: String, msg: String, th: Throwable?) {
        if (verboseEnabled) {
            if (th != null) Log.d(tag, "$GLOBAL_PREFIX $msg", th)
            else Log.d(tag, "$GLOBAL_PREFIX $msg")
        }
    }

    fun i(tag: String, msg: String) {
        if (verboseEnabled) Log.i(tag, "$GLOBAL_PREFIX $msg")
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, "$GLOBAL_PREFIX $msg")
    }

    fun w(tag: String, msg: String, th: Throwable?) {
        if (th != null) Log.w(tag, "$GLOBAL_PREFIX $msg", th)
        else Log.w(tag, "$GLOBAL_PREFIX $msg")
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, "$GLOBAL_PREFIX $msg")
    }

    fun e(tag: String, msg: String, th: Throwable?) {
        if (th != null) Log.e(tag, "$GLOBAL_PREFIX $msg", th)
        else Log.e(tag, "$GLOBAL_PREFIX $msg")
    }

    /** Краткое представление строки для логов (без полных ключей/токенов). */
    fun mask(s: String?, visibleStart: Int = 6, visibleEnd: Int = 4): String {
        if (s.isNullOrBlank()) return "null"
        if (s.length <= visibleStart + visibleEnd) return s
        return "${s.take(visibleStart)}...${s.takeLast(visibleEnd)}"
    }
}
