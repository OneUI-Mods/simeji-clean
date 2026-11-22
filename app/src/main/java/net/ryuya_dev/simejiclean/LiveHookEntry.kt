package net.ryuya_dev.simejiclean

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam

/** Rebinds the current keyboard to the updated module without recreating its input session. */
class LiveHookEntry : HookEntry() {
    @Synchronized
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val state = prepareLiveUpdate() ?: return false
        param.setSavedInstanceState(state)
        retireLiveUpdate()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        onModuleLoaded(param)
        val state = param.savedInstanceState as? Array<*> ?: return
        val context = state.getOrNull(0) as? Context ?: return
        val loader = state.getOrNull(1) as? ClassLoader ?: return
        if (ConfigProvider.HOST != context.packageName) return
        val keyboard = state.getOrNull(2) as? InputMethodService
        val oldHooks = param.oldHookHandles
        Handler(Looper.getMainLooper()).post {
            restoreInputMethod(keyboard)
            if (!bootstrap(context, loader, context.applicationInfo)) return@post
            oldHooks.forEach { it.unhook() }
            HookEntry.log("ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}")
        }
    }
}
