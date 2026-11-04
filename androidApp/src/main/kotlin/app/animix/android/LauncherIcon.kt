package app.animix.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.animix.core.AppIcon

/**
 * «Внешний вид → Значок приложения»: one launcher alias of MainActivity per icon, exactly one enabled.
 * A turquoise icon above the reached level shows as the classic one, like its theme.
 */
object LauncherIcon {
    private fun alias(icon: AppIcon) = "app.animix.android.Launcher${icon.name}"

    fun apply(context: Context, chosen: AppIcon, level: Int) {
        val icon = chosen.takeIf { level >= it.unlockLevel } ?: AppIcon.Classic
        val packages = context.packageManager
        runCatching {
            // Enable the new entry before disabling the old one, so the launcher never has none.
            val target = ComponentName(context, alias(icon))
            if (packages.getComponentEnabledSetting(target) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                packages.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (other in AppIcon.entries) if (other != icon) {
                val component = ComponentName(context, alias(other))
                if (packages.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                    packages.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { app.animix.core.AppLog.failure("LauncherIcon", "switch_failed", it) }
    }
}
