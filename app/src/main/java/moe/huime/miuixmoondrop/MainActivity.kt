/*
 * MiuixMoondrop — 应用 UI 入口
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 桌面的 LAUNCHER 图标直接拉起本 Activity（见 AndroidManifest 的 intent-filter）；
 * 快速弹窗（ui/PopupActivity.kt）的「更多设置」也按类名显式打开它。
 * 主题模式的读取/持久化与参考实现 _refs/OppoPods/.../MainActivity.kt:20-48 同一写法。
 * 首帧额外主动申请一次蓝牙/通知运行时权限（见 ui/Permissions.kt）：
 * 冷启动兜底连接需要 BLUETOOTH_CONNECT，不申请的话第一次打开只能等用户在系统里手动给。
 */
package moe.huime.miuixmoondrop

import moe.huime.miuixmoondrop.core.MoondropCatalog

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import moe.huime.miuixmoondrop.ui.App
import moe.huime.miuixmoondrop.ui.RequestRuntimePermissionsOnLaunch
import moe.huime.miuixmoondrop.ui.loadThemeMode
import moe.huime.miuixmoondrop.ui.saveThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 官方云端机型目录（与官方 APP 同源）：后台拉一次，失败无副作用
        Thread { runCatching { MoondropCatalog.refresh() } }.start()
        super.onCreate(savedInstanceState)

        setContent {
            // 应用打开时主动申请蓝牙/通知权限（已授权则什么都不做）
            RequestRuntimePermissionsOnLaunch()

            val context = LocalContext.current
            val themeMode = remember { mutableStateOf(loadThemeMode(context)) }
            val darkMode = when (themeMode.value) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )

                window.isNavigationBarContrastEnforced = false // Xiaomi moment, this code must be here

                onDispose {}
            }

            App(
                themeMode = themeMode,
                onThemeModeChange = {
                    themeMode.value = it
                    saveThemeMode(context, it)
                },
            )
        }
    }
}
