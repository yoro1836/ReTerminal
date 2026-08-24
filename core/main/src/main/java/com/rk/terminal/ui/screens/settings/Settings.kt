package com.rk.terminal.ui.screens.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.navigation.NavController
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.terminal.backend.avf.AvfImageInstaller
import com.rk.terminal.ui.components.SettingsToggle
import com.rk.terminal.ui.routes.MainActivityRoutes

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    title: @Composable () -> Unit,
    description: @Composable () -> Unit = {},
    startWidget: (@Composable () -> Unit)? = null,
    endWidget: (@Composable () -> Unit)? = null,
    isEnabled: Boolean = true,
    onClick: () -> Unit,
) {
    PreferenceTemplate(
        modifier = modifier.combinedClickable(
            enabled = isEnabled,
            indication = ripple(),
            interactionSource = interactionSource,
            onClick = onClick,
        ),
        contentModifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 16.dp)
            .padding(start = 16.dp),
        title = title,
        description = description,
        startWidget = startWidget,
        endWidget = endWidget,
        applyPaddings = false,
    )
}

object WorkingMode {
    const val ALPINE = 0
    const val ANDROID = 1
    const val AVF = 2
    const val SSH = 3
}

object InputMode {
    const val DEFAULT = 0
    const val TYPE_NULL = 1
    const val VISIBLE_PASSWORD = 2
}

@Composable
fun Settings(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var storageAccessGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val storageAccessLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val granted = Environment.isExternalStorageManager()
            storageAccessGranted = granted
            if (!granted) Settings.avfSharedMediaEnabled = false
        }
    var memoryGiB by remember { mutableFloatStateOf(Settings.avfMemoryMb / 1024f) }
    val maxCpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    var cpuCount by remember {
        mutableFloatStateOf(Settings.avfCpuCount.coerceIn(1, maxCpuCount).toFloat())
    }
    var inputMode by remember { mutableIntStateOf(Settings.input_mode) }
    val imageInfo = AvfImageInstaller.installationInfo(context)
    val avfSupported = Build.VERSION.SDK_INT >= 36 &&
        context.packageManager.hasSystemFeature("android.software.virtualization_framework")
    val vmPermissionsGranted = listOf(
        "android.permission.MANAGE_VIRTUAL_MACHINE",
        "android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
    ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    PreferenceLayout(
        label = stringResource(strings.settings),
        modifier = modifier,
        onBack = { navController.popBackStack() },
    ) {
        PreferenceGroup(heading = "AVF status") {
            SettingsCard(
                title = { Text(if (avfSupported) "AVF available" else "AVF unavailable") },
                description = {
                    Text(
                        when {
                            Build.VERSION.SDK_INT < 36 -> "Android 16 or newer is required"
                            !avfSupported -> "This device does not expose Android Virtualization Framework"
                            !vmPermissionsGranted -> "ADB grant is required for the two AVF permissions"
                            else -> "Android ${Build.VERSION.RELEASE} · AVF permissions granted"
                        },
                    )
                },
                onClick = {},
            )
            SettingsCard(
                title = { Text(Settings.avfGuestOsName) },
                description = {
                    val imageDescription = if (imageInfo.installed) {
                        val sizeMiB = imageInfo.diskBytes / (1024L * 1024L)
                        "Installed · $sizeMiB MiB · writable disk"
                    } else {
                        "Downloaded and checksum-verified automatically on first launch"
                    }
                    Text(imageDescription)
                },
                onClick = {},
            )
        }

        PreferenceGroup(heading = "VM memory") {
            PreferenceTemplate(
                title = { Text("${memoryGiB.roundToInt()} GiB") },
                description = { Text("Guest memory · applied after VM restart") },
            ) {}
            PreferenceTemplate(title = {}) {
                Slider(
                    value = memoryGiB,
                    onValueChange = { memoryGiB = it.roundToInt().toFloat() },
                    onValueChangeFinished = {
                        Settings.avfMemoryMb = memoryGiB.roundToInt() * 1024
                    },
                    valueRange = 1f..8f,
                    steps = 6,
                )
            }
            SettingsToggle(
                label = "Automatic memory ballooning",
                description = "Return guest memory when Android is under pressure · applied after VM restart",
                showSwitch = true,
                default = Settings.avfMemoryBalloonEnabled,
                sideEffect = { Settings.avfMemoryBalloonEnabled = it },
            )
        }

        PreferenceGroup(heading = "VM processors") {
            PreferenceTemplate(
                title = { Text("${cpuCount.roundToInt()} vCPU") },
                description = { Text("Guest-visible processors · applied after VM restart") },
            ) {}
            PreferenceTemplate(title = {}) {
                Slider(
                    value = cpuCount,
                    onValueChange = { cpuCount = it.roundToInt().toFloat() },
                    onValueChangeFinished = {
                        Settings.avfCpuCount = cpuCount.roundToInt()
                    },
                    valueRange = 1f..maxCpuCount.toFloat(),
                    steps = (maxCpuCount - 2).coerceAtLeast(0),
                    enabled = maxCpuCount > 1,
                )
            }
        }

        PreferenceGroup(heading = "VM network") {
            SettingsToggle(
                label = "Enable network",
                description = "Expose an AVF virtual network interface to Debian · applied after VM restart",
                showSwitch = true,
                default = Settings.avfNetworkEnabled,
                sideEffect = { Settings.avfNetworkEnabled = it },
            )
        }


        PreferenceGroup(heading = "VM storage") {
            SettingsToggle(
                label = "Automatic storage expansion",
                description = "Grow the sparse Debian disk with available device storage · disabling does not shrink it",
                showSwitch = true,
                default = Settings.avfStorageAutoExpandEnabled,
                sideEffect = { Settings.avfStorageAutoExpandEnabled = it },
            )
            SettingsToggle(
                label = "Storage ballooning",
                description = "Reserve guest free space to protect Android host storage · applied after VM restart",
                showSwitch = true,
                default = Settings.avfStorageBalloonEnabled,
                sideEffect = { Settings.avfStorageBalloonEnabled = it },
            )
            SettingsCard(
                title = {
                    Text(if (storageAccessGranted) "Host storage access granted" else "Grant host storage access")
                },
                description = {
                    Text("Android all-files access is required to share /sdcard with the guest")
                },
                onClick = {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${context.packageName}"))
                    storageAccessLauncher.launch(intent)
                },
            )
            SettingsToggle(
                label = "Share /sdcard as /mnt/media",
                description = if (storageAccessGranted) {
                    "Expose the host media directory to Debian · applied after VM restart"
                } else {
                    "Grant host storage access above before enabling this option"
                },
                showSwitch = true,
                default = Settings.avfSharedMediaEnabled && storageAccessGranted,
                isEnabled = storageAccessGranted,
                sideEffect = { Settings.avfSharedMediaEnabled = it },
            )
        }

        PreferenceGroup(heading = stringResource(strings.input_mode)) {
            InputModeOption(
                title = stringResource(strings.input_mode_default),
                description = stringResource(strings.input_mode_default_desc),
                mode = InputMode.DEFAULT,
                currentMode = inputMode,
            ) {
                inputMode = it
                Settings.input_mode = it
            }
            InputModeOption(
                title = stringResource(strings.input_mode_type_null),
                description = stringResource(strings.input_mode_type_null_desc),
                mode = InputMode.TYPE_NULL,
                currentMode = inputMode,
            ) {
                inputMode = it
                Settings.input_mode = it
            }
            InputModeOption(
                title = stringResource(strings.input_mode_visible_password),
                description = stringResource(strings.input_mode_visible_password_desc),
                mode = InputMode.VISIBLE_PASSWORD,
                currentMode = inputMode,
            ) {
                inputMode = it
                Settings.input_mode = it
            }
        }

        PreferenceGroup {
            SettingsCard(
                title = { Text(stringResource(strings.customizations)) },
                description = { Text("Terminal appearance, keyboard, toolbar, and shortcuts") },
                onClick = { navController.navigate(MainActivityRoutes.Customization.route) },
                endWidget = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.padding(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun RadioOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    SettingsCard(
        title = { Text(title) },
        description = { Text(description) },
        startWidget = {
            RadioButton(
                modifier = Modifier.padding(start = 8.dp),
                selected = selected,
                onClick = onSelect,
            )
        },
        onClick = onSelect,
    )
}

@Composable
private fun InputModeOption(
    title: String,
    description: String,
    mode: Int,
    currentMode: Int,
    onSelect: (Int) -> Unit,
) {
    RadioOption(
        title = title,
        description = description,
        selected = currentMode == mode,
        onSelect = { onSelect(mode) },
    )
}
