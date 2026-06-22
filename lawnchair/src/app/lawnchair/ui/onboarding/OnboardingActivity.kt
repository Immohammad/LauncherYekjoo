/*
 * Copyright 2026, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package app.lawnchair.ui.onboarding

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.net.Uri
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.isDefaultLauncher
import com.android.launcher3.R

class OnboardingActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Force the onboarding to render in Persian regardless of the system locale, so the
        // permission rationale always shows in fa. Scope is intentionally this activity only.
        val locale = Locale("fa", "IR")
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = PreferenceManager.getInstance(this)

        setContent {
            LawnchairTheme {
                EdgeToEdge()
                if (prefs.onboardingCompleted.get()) {
                    SetupScreen(onDone = { finish() })
                } else {
                    OnboardingScreen(
                        onComplete = {
                            prefs.onboardingCompleted.set(true)
                            prefs.onboardingLastReminderMs.set(System.currentTimeMillis())
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, OnboardingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

private enum class OnboardingStep { WELCOME, DEFAULT_LAUNCHER, NOTIFICATIONS, DONE }

@Composable
private fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var isDefault by remember { mutableStateOf(context.isDefaultLauncher()) }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }

    // Re-check state every time the activity resumes (user might come back from Settings)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefault = context.isDefaultLauncher()
                notifGranted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val defaultLauncherRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        isDefault = context.isDefaultLauncher()
        if (isDefault) step = nextStep(step, context)
    }

    val notifPermissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifGranted = granted
        step = nextStep(step, context)
    }

    // Auto-skip steps that are already satisfied (e.g. user opened onboarding but is already default)
    LaunchedEffect(step, isDefault, notifGranted) {
        if (step == OnboardingStep.DEFAULT_LAUNCHER && isDefault) {
            step = nextStep(step, context)
        } else if (step == OnboardingStep.NOTIFICATIONS && (notifGranted || !needsNotificationPermission())) {
            step = nextStep(step, context)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.systemBarsPadding()) {
            when (step) {
                OnboardingStep.WELCOME -> StepContent(
                    icon = Icons.Rounded.Waves,
                    title = stringResource(R.string.onboarding_welcome_title),
                    description = stringResource(R.string.onboarding_welcome_description),
                    primaryLabel = stringResource(R.string.onboarding_get_started),
                    onPrimary = { step = nextStep(step, context) },
                    secondaryLabel = stringResource(R.string.onboarding_skip),
                    onSecondary = onComplete,
                )

                OnboardingStep.DEFAULT_LAUNCHER -> StepContent(
                    icon = Icons.Rounded.Home,
                    title = stringResource(R.string.onboarding_default_launcher_title),
                    description = stringResource(R.string.onboarding_default_launcher_description),
                    primaryLabel = if (isDefault) {
                        stringResource(R.string.onboarding_continue)
                    } else {
                        stringResource(R.string.onboarding_set_default)
                    },
                    onPrimary = {
                        if (isDefault) {
                            step = nextStep(step, context)
                        } else {
                            defaultLauncherRequest.launch(buildDefaultLauncherIntent(context))
                        }
                    },
                    secondaryLabel = stringResource(R.string.onboarding_skip),
                    onSecondary = { step = nextStep(step, context) },
                )

                OnboardingStep.NOTIFICATIONS -> StepContent(
                    icon = Icons.Rounded.Notifications,
                    title = stringResource(R.string.onboarding_notifications_title),
                    description = stringResource(R.string.onboarding_notifications_description),
                    primaryLabel = stringResource(R.string.onboarding_allow_notifications),
                    onPrimary = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            step = nextStep(step, context)
                        }
                    },
                    secondaryLabel = stringResource(R.string.onboarding_skip),
                    onSecondary = { step = nextStep(step, context) },
                )

                OnboardingStep.DONE -> StepContent(
                    icon = Icons.Rounded.CheckCircle,
                    title = stringResource(R.string.onboarding_done_title),
                    description = stringResource(R.string.onboarding_done_description),
                    primaryLabel = stringResource(R.string.onboarding_finish),
                    onPrimary = onComplete,
                    secondaryLabel = null,
                    onSecondary = null,
                )
            }
        }
    }
}

@Composable
private fun StepContent(
    icon: ImageVector,
    title: String,
    description: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            Text(primaryLabel, style = MaterialTheme.typography.titleMedium)
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                Text(secondaryLabel)
            }
        }
    }
}

private fun nextStep(current: OnboardingStep, context: Context): OnboardingStep = when (current) {
    OnboardingStep.WELCOME -> if (context.isDefaultLauncher()) {
        nextStep(OnboardingStep.DEFAULT_LAUNCHER, context)
    } else {
        OnboardingStep.DEFAULT_LAUNCHER
    }
    OnboardingStep.DEFAULT_LAUNCHER -> if (needsNotificationPermission() && !hasNotificationPermission(context)) {
        OnboardingStep.NOTIFICATIONS
    } else {
        OnboardingStep.DONE
    }
    OnboardingStep.NOTIFICATIONS -> OnboardingStep.DONE
    OnboardingStep.DONE -> OnboardingStep.DONE
}

private fun buildDefaultLauncherIntent(context: Context): Intent {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService<RoleManager>()
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            return roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }
    }
    return Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun needsNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

private fun hasNotificationPermission(context: Context): Boolean {
    if (!needsNotificationPermission()) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(context.isDefaultLauncher()) }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefault = context.isDefaultLauncher()
                notifGranted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val defaultLauncherRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { isDefault = context.isDefaultLauncher() }

    val notifPermissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notifGranted = granted }

    val iconBitmap = remember {
        runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(192, 192).asImageBitmap()
        }.getOrNull()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                        )
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.derived_app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.onboarding_setup_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))

                SetupRow(
                    icon = Icons.Rounded.Home,
                    title = stringResource(R.string.onboarding_default_launcher_title),
                    subtitle = if (isDefault) {
                        stringResource(R.string.onboarding_active)
                    } else {
                        stringResource(R.string.onboarding_launcher_inactive)
                    },
                    isDone = isDefault,
                    buttonLabel = if (isDefault) {
                        stringResource(R.string.onboarding_change)
                    } else {
                        stringResource(R.string.onboarding_set_default)
                    },
                    onButton = {
                        val intent = if (isDefault) {
                            Intent(Settings.ACTION_HOME_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        } else {
                            buildDefaultLauncherIntent(context)
                        }
                        defaultLauncherRequest.launch(intent)
                    },
                )

                if (needsNotificationPermission() && !notifGranted) {
                    Spacer(Modifier.height(12.dp))
                    SetupRow(
                        icon = Icons.Rounded.Notifications,
                        title = stringResource(R.string.onboarding_notifications_title),
                        subtitle = stringResource(R.string.onboarding_not_allowed),
                        isDone = false,
                        buttonLabel = stringResource(R.string.onboarding_allow_notifications),
                        onButton = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }

                Spacer(Modifier.weight(1f))

                SwipeHintCard()

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SwipeHintCard() {
    val context = LocalContext.current
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var isFinished by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.onboarding_swipe_hint_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .height(264.dp)
                .aspectRatio(9f / 16f)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.Black)
                .clickable {
                    videoView?.apply {
                        seekTo(0)
                        start()
                        isFinished = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(
                            Uri.parse("android.resource://${context.packageName}/${R.raw.swipe_demo}"),
                        )
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            mp.setVolume(0f, 0f)
                            start()
                            isFinished = false
                        }
                        setOnCompletionListener { isFinished = true }
                        videoView = this
                    }
                },
            )

            if (isFinished) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(72.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isDone: Boolean,
    buttonLabel: String,
    onButton: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isDone) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isDone) Icons.Rounded.CheckCircle else icon,
                    contentDescription = null,
                    tint = if (isDone) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDone) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Button(
                onClick = onButton,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(buttonLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
