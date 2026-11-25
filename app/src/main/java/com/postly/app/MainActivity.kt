package com.postly.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.postly.app.data.model.Platform
import com.postly.app.data.model.ScheduleEntity
import com.postly.app.service.AlarmReceiver
import com.postly.app.service.AlarmScheduler
import com.postly.app.service.DispatchForegroundService
import com.postly.app.ui.screens.CreateScheduleScreen
import com.postly.app.ui.screens.MainSchedulerScreen
import com.postly.app.ui.screens.NavDrawerContent
import com.postly.app.ui.screens.RequirementsScreen
import com.postly.app.ui.theme.BrandBlue
import com.postly.app.ui.theme.PostlyTheme
import com.postly.app.ui.theme.TextPrimary
import com.postly.app.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

sealed class Screen {
    object Main : Screen()
    data class Create(val platform: Platform) : Screen()
    object Requirements : Screen()
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("postly_prefs", Context.MODE_PRIVATE)

        val app = application as PostlyApplication
        val scheduleDao = app.database.scheduleDao()

        setContent {
            PostlyTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                val schedules by scheduleDao.getAllSchedules().collectAsState(initial = emptyList())
                var showDisclosureDialog by remember {
                    mutableStateOf(!prefs.getBoolean("disclosure_accepted", false))
                }

                // Play Store Mandatory Prominent In-App Disclosure Dialog
                if (showDisclosureDialog) {
                    AlertDialog(
                        onDismissRequest = { /* Must explicitly accept or decline */ },
                        title = {
                            Text(
                                text = "Accessibility Service Disclosure",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextPrimary
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Postly uses Android's AccessibilityService API to automate message dispatching on WhatsApp at your requested schedule.\n\n" +
                                            "How it is used:\n" +
                                            "• Finds recipient chat/group in WhatsApp\n" +
                                            "• Inputs the pre-scheduled message content\n" +
                                            "• Clicks the Send button\n\n" +
                                            "Data Privacy:\n" +
                                            "Postly does NOT collect, store, or transmit your private chats, passwords, keystrokes, or personal credentials. All operations run locally on your device.",
                                    fontSize = 13.sp,
                                    color = TextSecondary,
                                    lineHeight = 18.sp
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    prefs.edit().putBoolean("disclosure_accepted", true).apply()
                                    showDisclosureDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                            ) {
                                Text("I Agree & Continue")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showDisclosureDialog = false
                                }
                            ) {
                                Text("Decline")
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color.White
                    )
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            NavDrawerContent(
                                onNavigate = { destination ->
                                    if (destination == "requirements") {
                                        currentScreen = Screen.Requirements
                                    }
                                },
                                onCloseDrawer = {
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    }
                ) {
                    when (val screen = currentScreen) {
                        is Screen.Main -> {
                            MainSchedulerScreen(
                                schedules = schedules,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onNavigateCreate = { platform ->
                                    currentScreen = Screen.Create(platform)
                                },
                                onOpenRequirements = {
                                    currentScreen = Screen.Requirements
                                },
                                onDeleteSchedule = { schedule ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        AlarmScheduler.cancelSchedule(this@MainActivity, schedule.id)
                                        scheduleDao.markDeleted(schedule.id)
                                    }
                                },
                                onSendNow = { schedule ->
                                    val serviceIntent = Intent(this@MainActivity, DispatchForegroundService::class.java).apply {
                                        putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, schedule.id)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(serviceIntent)
                                    } else {
                                        startService(serviceIntent)
                                    }
                                },
                                onClearPending = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        scheduleDao.clearAllPending()
                                    }
                                }
                            )
                        }
                        is Screen.Create -> {
                            CreateScheduleScreen(
                                platform = screen.platform,
                                onBack = { currentScreen = Screen.Main },
                                onOpenRequirements = { currentScreen = Screen.Requirements },
                                onSaveSchedule = { newSchedule ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val newId = scheduleDao.insertSchedule(newSchedule)
                                        if (newSchedule.status == "PENDING") {
                                            val saved = newSchedule.copy(id = newId)
                                            AlarmScheduler.scheduleMessage(this@MainActivity, saved)
                                        }
                                    }
                                    currentScreen = Screen.Main
                                }
                            )
                        }
                        is Screen.Requirements -> {
                            RequirementsScreen(
                                onBack = { currentScreen = Screen.Main }
                            )
                        }
                    }
                }
            }
        }
    }
}
