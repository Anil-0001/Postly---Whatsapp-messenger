package com.postly.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.postly.app.service.PostlyAccessibilityService
import com.postly.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequirementsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("postly_prefs", Context.MODE_PRIVATE) }
    var savedPin by remember { mutableStateOf(prefs.getString("device_screen_pin", "") ?: "") }
    var isPinDialogOpen by remember { mutableStateOf(false) }

    val isAccessibilityEnabled = PostlyAccessibilityService.isServiceRunning()

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } else true

    val canDrawOverlays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Requirement Details",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = BrandBlue)
                    }
                },
                actions = {
                    TextButton(onClick = { /* Learn more */ }) {
                        Text("Learn more", color = BrandBlue, fontSize = 14.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BackgroundLight)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: Accessibility
            RequirementCard(
                title = "Accessibility",
                isComplete = isAccessibilityEnabled,
                description = "Enable Accessibility for Postly for automated message sending. Postly searches for contacts, types messages, and clicks send on WhatsApp on your schedule.",
                actionButtonText = if (isAccessibilityEnabled) "Accessibility Enabled" else "Enable Accessibility",
                onAction = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )

            // Card 2: Battery Optimization
            RequirementCard(
                title = "Battery Optimization",
                isComplete = isIgnoringBatteryOptimizations,
                description = "Turn off Battery Optimization for Postly and choose 'Don't Optimize' to prevent background task killing and ensure on-time delivery.",
                actionButtonText = if (isIgnoringBatteryOptimizations) "Optimized: Don't Optimize" else "Disable Optimization",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    }
                }
            )

            // Card 3: Display over other apps
            RequirementCard(
                title = "Activate Multiple Contact Selection",
                isComplete = canDrawOverlays,
                description = "Grant Postly permission to 'Display over other apps' to enable multi-selection overlay when picking WhatsApp contacts.",
                actionButtonText = if (canDrawOverlays) "Display Over Other Apps Allowed" else "Enable Display Over Other Apps",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                }
            )

            // Card 4: Bypass Screen Lock (Matching SKEDit exact card & PIN input)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bypass Screen Lock",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        if (savedPin.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "PIN Set",
                                tint = SuccessGreen,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "To start the sending process automatically at the scheduled time, complete one of these actions:\n" +
                                "1. Enter your device's 'pin' or 'password' in Postly settings to enable auto unlock of your screen.\n" +
                                "2. Activate smart lock from your device settings.\n" +
                                "3. Remove your screen lock by setting it to 'swipe' or 'none'.\n\n" +
                                "Otherwise, enable 'ask me before sending' when scheduling and you'll get a notification to click on at the scheduled time to start the auto-sending.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Button 1: Provide PIN or Password
                    Button(
                        onClick = { isPinDialogOpen = true },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (savedPin.isEmpty()) "Provide Pin or Password" else "Update Screen Lock PIN (Set: ••••)",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Button 2: Enable Smart Lock
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlueLight),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enable Smart Lock", color = BrandBlue, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Button 3: Disable Screen Lock
                    TextButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Disable Screen Lock", color = BrandBlue, fontSize = 13.sp)
                    }
                }
            }

            // Card 5: Mi / Xiaomi / Oppo / Vivo Background & Autostart Settings
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Mi / Xiaomi / Device Autostart",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = "Device Settings",
                            tint = WarningOrange,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "For Xiaomi / Redmi / POCO / Vivo / Oppo / Realme devices, the phone stops background apps unless you enable these 2 settings:\n\n" +
                                "1. Autostart: Turn ON Autostart so Postly stays active in the background.\n" +
                                "2. Other Permissions: Allow 'Display pop-up windows while running in the background' and 'Show on Lock screen'.\n" +
                                "3. Battery Saver: Set to 'No restrictions'.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val intents = listOf(
                                // Xiaomi / MIUI / HyperOS Autostart
                                Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                                // Oppo / Realme Autostart
                                Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                                Intent().setComponent(android.content.ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")),
                                // Vivo Autostart
                                Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                                // Fallback App Details
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") }
                            )
                            for (intent in intents) {
                                try {
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    context.startActivity(intent)
                                    break
                                } catch (e: Exception) {}
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("1. Open Autostart Settings", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            try {
                                // Xiaomi / MIUI Permissions Editor
                                val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                                    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                                    putExtra("extra_pkgname", context.packageName)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(miuiIntent)
                            } catch (e: Exception) {
                                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(fallback)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlueLight),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("2. Open Pop-up & Lock Screen Permissions", color = BrandBlue, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }

            // Card 6: Notifications
            RequirementCard(
                title = "Notifications",
                isComplete = true,
                description = "Turn on Postly notifications to ensure your messages are delivered on time and to receive status alerts.",
                actionButtonText = "Notification Settings",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                }
            )

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    // Dialog: Provide PIN or Password (Matching SKEDit)
    if (isPinDialogOpen) {
        var tempPin by remember { mutableStateOf(savedPin) }
        AlertDialog(
            onDismissRequest = { isPinDialogOpen = false },
            title = { Text("Device Screen Lock PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter your phone screen lock PIN so Postly can automatically unlock your device and send scheduled WhatsApp messages when your screen is locked.",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = tempPin,
                        onValueChange = { tempPin = it },
                        label = { Text("Device Lock PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.edit().putString("device_screen_pin", tempPin).apply()
                        savedPin = tempPin
                        isPinDialogOpen = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { isPinDialogOpen = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun RequirementCard(
    title: String,
    isComplete: Boolean,
    description: String,
    actionButtonText: String,
    onAction: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (isComplete) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Complete",
                        tint = SuccessGreen,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = "Pending",
                        tint = WarningOrange,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isComplete) BrandBlueLight else BrandBlue
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = actionButtonText,
                    color = if (isComplete) BrandBlue else Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
