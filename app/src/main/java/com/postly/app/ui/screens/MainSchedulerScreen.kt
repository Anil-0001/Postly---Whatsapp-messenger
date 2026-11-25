package com.postly.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.postly.app.data.model.Platform
import com.postly.app.data.model.ScheduleEntity
import com.postly.app.data.model.ScheduleStatus
import com.postly.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSchedulerScreen(
    schedules: List<ScheduleEntity>,
    onOpenDrawer: () -> Unit,
    onNavigateCreate: (Platform) -> Unit,
    onOpenRequirements: () -> Unit,
    onDeleteSchedule: (ScheduleEntity) -> Unit,
    onSendNow: (ScheduleEntity) -> Unit,
    onClearPending: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(ScheduleStatus.PENDING) }
    var isFabMenuExpanded by remember { mutableStateOf(false) }
    var isTopMenuExpanded by remember { mutableStateOf(false) }

    val filteredSchedules = remember(schedules, selectedTab) {
        schedules.filter { it.status == selectedTab.name }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Scheduler",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = BrandBlue
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Dropdown",
                            tint = BrandBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { /* Gift / Premium */ }) {
                        Icon(Icons.Outlined.CardGiftcard, contentDescription = "Gift", tint = BrandBlue)
                    }
                    IconButton(onClick = { /* Search */ }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
                    }
                    Box {
                        IconButton(onClick = { isTopMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded = isTopMenuExpanded,
                            onDismissRequest = { isTopMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Filter") },
                                trailingIcon = { Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp)) },
                                onClick = { isTopMenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Requirements Check") },
                                onClick = {
                                    isTopMenuExpanded = false
                                    onOpenRequirements()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Pending") },
                                onClick = {
                                    isTopMenuExpanded = false
                                    onClearPending()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        floatingActionButton = {
            Box(contentAlignment = Alignment.BottomEnd) {
                // Expanded Platform Selector Menu
                if (isFabMenuExpanded) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 72.dp)
                    ) {
                        PlatformFabItem(title = "Whatsapp", isFunctional = true) {
                            isFabMenuExpanded = false
                            onNavigateCreate(Platform.WHATSAPP)
                        }
                        PlatformFabItem(title = "Whatsapp business", isFunctional = false) {
                            isFabMenuExpanded = false
                            onNavigateCreate(Platform.WHATSAPP_BUSINESS)
                        }
                        PlatformFabItem(title = "Telegram", isFunctional = false) {
                            isFabMenuExpanded = false
                            onNavigateCreate(Platform.TELEGRAM)
                        }
                        PlatformFabItem(title = "Messenger", isFunctional = false) {
                            isFabMenuExpanded = false
                            onNavigateCreate(Platform.MESSENGER)
                        }
                        PlatformFabItem(title = "Instagram", isFunctional = false) {
                            isFabMenuExpanded = false
                            onNavigateCreate(Platform.INSTAGRAM)
                        }
                        PlatformFabItem(title = "Email", isFunctional = false) {
                            isFabMenuExpanded = false
                            onNavigateCreate(Platform.EMAIL)
                        }
                        PlatformFabItem(title = "Call", isFunctional = false) {
                            isFabMenuExpanded = false
                            onNavigateCreate(Platform.CALL)
                        }
                    }
                }

                // Main Floating Action Button
                FloatingActionButton(
                    onClick = { isFabMenuExpanded = !isFabMenuExpanded },
                    containerColor = BrandBlue,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (isFabMenuExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Add"
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BackgroundLight)
        ) {
            // Horizontal Filter Tabs: [Pending, Done, Deleted, Failed, Drafts]
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 16.dp,
                containerColor = Color.White,
                divider = {},
                indicator = {}
            ) {
                ScheduleStatus.values().forEach { status ->
                    val isSelected = selectedTab == status
                    Surface(
                        modifier = Modifier
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { selectedTab = status },
                        color = if (isSelected) BrandBlueLight else Color.Transparent,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = status.title,
                            color = if (isSelected) BrandBlue else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Body: Empty State or Scheduled List
            if (filteredSchedules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(BrandBlueLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AccessTime,
                                contentDescription = "Time",
                                tint = BrandBlue,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No scheduled messages",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap + to schedule your first message",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredSchedules, key = { it.id }) { schedule ->
                        ScheduleCard(
                            schedule = schedule,
                            onDelete = { onDeleteSchedule(schedule) },
                            onSendNow = { onSendNow(schedule) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleCard(
    schedule: ScheduleEntity,
    onDelete: () -> Unit,
    onSendNow: () -> Unit
) {
    var isMenuOpen by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("EEE, MMM dd - hh:mm a", Locale.getDefault()) }
    val formattedDate = remember(schedule.scheduledTimeMillis) {
        dateFormatter.format(Date(schedule.scheduledTimeMillis))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Platform, Date, Status checkmark, More menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = schedule.platform,
                        color = WhatsAppGreen,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formattedDate,
                        color = BrandBlue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (schedule.status == ScheduleStatus.DONE.name) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Done",
                            tint = BrandBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { isMenuOpen = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = isMenuOpen,
                        onDismissRequest = { isMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Send Now") },
                            leadingIcon = { Icon(Icons.Default.Send, null, Modifier.size(18.dp)) },
                            onClick = {
                                isMenuOpen = false
                                onSendNow()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)) },
                            onClick = {
                                isMenuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            if (schedule.title.isNotBlank()) {
                Text(
                    text = schedule.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Recipient
            Text(
                text = "To: ${schedule.recipientName.ifBlank { schedule.recipientPhone.ifBlank { "Contact" } }}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Message preview
            Text(
                text = schedule.message,
                fontSize = 13.sp,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (!schedule.attachmentUri.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = BrandBlueLight,
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (schedule.attachmentType) {
                                "IMAGE" -> Icons.Default.Image
                                "VIDEO" -> Icons.Default.Videocam
                                "AUDIO" -> Icons.Default.Headphones
                                else -> Icons.Default.AttachFile
                            },
                            contentDescription = null,
                            tint = BrandBlue,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = schedule.attachmentName ?: "Attachment",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = BrandBlueDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlatformFabItem(
    title: String,
    isFunctional: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = TextPrimary
            )
        }
    }
}
