package com.postly.app.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.postly.app.data.model.Platform
import com.postly.app.data.model.RepeatRule
import com.postly.app.data.model.ScheduleEntity
import com.postly.app.data.model.ScheduleStatus
import com.postly.app.service.PostlyAccessibilityService
import com.postly.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScheduleScreen(
    platform: Platform,
    initialContactName: String = "",
    onBack: () -> Unit,
    onOpenRequirements: () -> Unit,
    onSaveSchedule: (ScheduleEntity) -> Unit
) {
    val context = LocalContext.current
    val calendar = remember { Calendar.getInstance().apply { add(Calendar.MINUTE, 5) } }

    var recipientName by remember { mutableStateOf(initialContactName) }
    var recipientPhone by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var scheduledMillis by remember { mutableStateOf(calendar.timeInMillis) }
    var repeatRule by remember { mutableStateOf(RepeatRule.NO_REPEAT) }
    var askBeforeSending by remember { mutableStateOf(false) }
    var generateThumbnails by remember { mutableStateOf(false) }

    var isRecipientMenuOpen by remember { mutableStateOf(false) }
    var isManualContactDialogOpen by remember { mutableStateOf(false) }
    var isRepeatMenuOpen by remember { mutableStateOf(false) }
    var isTopMenuOpen by remember { mutableStateOf(false) }

    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val timeFormatter = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    var displayDate by remember { mutableStateOf(dateFormatter.format(Date(scheduledMillis))) }
    var displayTime by remember { mutableStateOf(timeFormatter.format(Date(scheduledMillis))) }

    // System Phonebook Picker Launcher
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        if (uri != null) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        recipientName = it.getString(nameIdx) ?: ""
                    }
                }
            }
        }
    }

    // Listen to contact picked via WhatsApp Accessibility
    LaunchedEffect(Unit) {
        PostlyAccessibilityService.onContactPickedCallback = { capturedName ->
            recipientName = capturedName
            recipientPhone = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = platform.displayName,
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
                    IconButton(onClick = onOpenRequirements) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Requirements",
                            tint = WarningOrange
                        )
                    }
                    Box {
                        IconButton(onClick = { isTopMenuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded = isTopMenuOpen,
                            onDismissRequest = { isTopMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Save to drafts") },
                                onClick = {
                                    isTopMenuOpen = false
                                    onSaveSchedule(
                                        ScheduleEntity(
                                            platform = platform.name,
                                            recipientName = recipientName,
                                            recipientPhone = recipientPhone,
                                            title = title,
                                            message = message,
                                            scheduledTimeMillis = scheduledMillis,
                                            repeatRule = repeatRule.name,
                                            askBeforeSending = askBeforeSending,
                                            status = ScheduleStatus.DRAFTS.name
                                        )
                                    )
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        if (recipientPhone.isNotBlank() || recipientName.isNotBlank()) {
                            onSaveSchedule(
                                ScheduleEntity(
                                    platform = platform.name,
                                    recipientName = recipientName,
                                    recipientPhone = recipientPhone,
                                    title = title,
                                    message = message,
                                    scheduledTimeMillis = scheduledMillis,
                                    repeatRule = repeatRule.name,
                                    askBeforeSending = askBeforeSending,
                                    status = ScheduleStatus.PENDING.name
                                )
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    enabled = message.isNotBlank() && (recipientPhone.isNotBlank() || recipientName.isNotBlank())
                ) {
                    Text(
                        text = "Schedule Message",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 4.dp)
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Schedule Type Selector
            OutlinedCard(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = Color.White)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Schedule Type", fontSize = 11.sp, color = TextSecondary)
                        Text("Contacts / Groups", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    }
                    Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                }
            }

            // Recipients Section
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recipients",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                IconButton(
                                    onClick = { isRecipientMenuOpen = true },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(BrandBlue, RoundedCornerShape(8.dp))
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
                                }

                                DropdownMenu(
                                    expanded = isRecipientMenuOpen,
                                    onDismissRequest = { isRecipientMenuOpen = false }
                                ) {
                                    // WhatsApp: Opens WhatsApp directly to pick any chat/group
                                    DropdownMenuItem(
                                        text = { Text("Whatsapp") },
                                        leadingIcon = { Icon(Icons.Default.Send, null, Modifier.size(18.dp)) },
                                        onClick = {
                                            isRecipientMenuOpen = false
                                            // Enable Accessibility contact picking mode
                                            PostlyAccessibilityService.isPickingContact = true
                                            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                                            if (launchIntent != null) {
                                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(launchIntent)
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Phonebook") },
                                        leadingIcon = { Icon(Icons.Default.MenuBook, null, Modifier.size(18.dp)) },
                                        onClick = {
                                            isRecipientMenuOpen = false
                                            contactPickerLauncher.launch(null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Postly Lists") },
                                        leadingIcon = { Icon(Icons.Default.Group, null, Modifier.size(18.dp)) },
                                        onClick = { isRecipientMenuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Upload CSV") },
                                        leadingIcon = { Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp)) },
                                        onClick = { isRecipientMenuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Enter Manually") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
                                        onClick = { isRecipientMenuOpen = false; isManualContactDialogOpen = true }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (recipientPhone.isNotBlank() || recipientName.isNotBlank()) "1/1" else "0/1",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    if (recipientPhone.isNotBlank() || recipientName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BrandBlueLight,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = recipientName.ifBlank { "WhatsApp Chat" },
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        color = BrandBlueDark
                                    )
                                    if (recipientPhone.isNotBlank()) {
                                        Text(text = recipientPhone, fontSize = 12.sp, color = TextSecondary)
                                    }
                                }
                                IconButton(
                                    onClick = { recipientName = ""; recipientPhone = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = DangerRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Title Field
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Title", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Multiple messages", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Checkbox(checked = false, onCheckedChange = {})
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 500) title = it },
                    placeholder = { Text("Title (Optional)", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    supportingText = {
                        Text("${title.length}/500", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                )
            }

            // Message Area
            Column {
                Text("Message", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    TextField(
                        value = message,
                        onValueChange = { if (it.length <= 500) message = it },
                        placeholder = { Text("Message...", color = TextSecondary) },
                        modifier = Modifier
                            .weight(1f)
                            .height(130.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BrandBlueLight,
                            modifier = Modifier
                                .size(36.dp)
                                .clickable { message += " {name} " }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("{ }", color = BrandBlue, fontWeight = FontWeight.Bold)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BrandBlueLight,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Feed, null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BrandBlueLight,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MyLocation, null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { /* AI Assistant */ }) {
                        Icon(Icons.Default.AutoAwesome, null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("AI Assistant", color = BrandBlue, fontSize = 13.sp)
                    }
                    Text("${message.length}/500", fontSize = 12.sp, color = TextSecondary)
                }
            }

            // Checkbox: Generate thumbnails from links
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = generateThumbnails,
                    onCheckedChange = { generateThumbnails = it }
                )
                Text("Generate thumbnails from links", fontSize = 14.sp, color = TextPrimary)
            }

            // Attachments
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Attachments", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {},
                        modifier = Modifier
                            .size(36.dp)
                            .background(BrandBlue, RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.AttachFile, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("0/0", fontSize = 13.sp, color = TextSecondary)
                }
            }

            // Schedule Section (Date & Time)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Schedule", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(36.dp)
                                .background(BrandBlue, RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Date Box
                        OutlinedCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val dateDialog = DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            calendar.set(Calendar.YEAR, y)
                                            calendar.set(Calendar.MONTH, m)
                                            calendar.set(Calendar.DAY_OF_MONTH, d)
                                            scheduledMillis = calendar.timeInMillis
                                            displayDate = dateFormatter.format(Date(scheduledMillis))
                                        },
                                        calendar.get(Calendar.YEAR),
                                        calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)
                                    )
                                    dateDialog.show()
                                },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Date", fontSize = 11.sp, color = TextSecondary)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.CalendarToday, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(displayDate, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                }
                            }
                        }

                        // Time Box
                        OutlinedCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val timeDialog = TimePickerDialog(
                                        context,
                                        { _, h, min ->
                                            calendar.set(Calendar.HOUR_OF_DAY, h)
                                            calendar.set(Calendar.MINUTE, min)
                                            scheduledMillis = calendar.timeInMillis
                                            displayTime = timeFormatter.format(Date(scheduledMillis))
                                        },
                                        calendar.get(Calendar.HOUR_OF_DAY),
                                        calendar.get(Calendar.MINUTE),
                                        false
                                    )
                                    timeDialog.show()
                                },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Time", fontSize = 11.sp, color = TextSecondary)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.AccessTime, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(displayTime, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Repeat Selector
                    Box {
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isRepeatMenuOpen = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Repeat, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(repeatRule.displayName, fontSize = 14.sp, color = TextPrimary)
                                }
                                Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                            }
                        }

                        DropdownMenu(
                            expanded = isRepeatMenuOpen,
                            onDismissRequest = { isRepeatMenuOpen = false }
                        ) {
                            RepeatRule.values().forEach { rule ->
                                DropdownMenuItem(
                                    text = { Text(rule.displayName) },
                                    onClick = {
                                        repeatRule = rule
                                        isRepeatMenuOpen = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlueLight),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Outlined.AccessTime, null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Check time conflict", color = BrandBlue, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlueLight),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Outlined.CalendarMonth, null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View Schedule Summary", color = BrandBlue, fontSize = 13.sp)
                    }
                }
            }

            // Ask me before sending switch
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Notifications, null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ask me before sending", fontSize = 14.sp, color = TextPrimary)
                    }
                    Switch(
                        checked = askBeforeSending,
                        onCheckedChange = { askBeforeSending = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = BrandBlue)
                    )
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }

    // Manual Contact Dialog (Only if user chooses "Enter Manually")
    if (isManualContactDialogOpen) {
        var tempName by remember { mutableStateOf(recipientName) }
        var tempPhone by remember { mutableStateOf(recipientPhone) }

        AlertDialog(
            onDismissRequest = { isManualContactDialogOpen = false },
            title = { Text("Enter WhatsApp Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("Contact Name (e.g. Friends)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tempPhone,
                        onValueChange = { tempPhone = it },
                        label = { Text("Phone Number with Country Code (e.g. +919876543210)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        recipientName = tempName
                        recipientPhone = tempPhone
                        isManualContactDialogOpen = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { isManualContactDialogOpen = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
