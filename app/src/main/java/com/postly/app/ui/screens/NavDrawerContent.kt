package com.postly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.postly.app.ui.theme.BrandBlue
import com.postly.app.ui.theme.TextPrimary
import com.postly.app.ui.theme.TextSecondary

@Composable
fun NavDrawerContent(
    onNavigate: (String) -> Unit,
    onCloseDrawer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(310.dp)
            .background(Color.White)
            .verticalScroll(rememberScrollState())
    ) {
        // Drawer Header (Sky blue header matching SKEDit)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandBlue)
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "GA",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }

                    // Social icons
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "IG", tint = Color.White, modifier = Modifier.size(20.dp))
                        Icon(Icons.Default.PlayArrow, contentDescription = "YT", tint = Color.White, modifier = Modifier.size(20.dp))
                        Icon(Icons.Default.Chat, contentDescription = "WA", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = "Free",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Guest account",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        }

        // Section: Paid Plans
        DrawerSectionHeader(title = "Paid Plans")
        DrawerMenuItem(icon = Icons.Outlined.Person, title = "Create Account") { onNavigate("auth"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.Autorenew, title = "Subscriptions") { onNavigate("subscriptions"); onCloseDrawer() }

        Divider(color = Color(0xFFF1F5F9))

        // Section: Features
        DrawerSectionHeader(title = "Features")
        DrawerMenuItem(icon = Icons.Outlined.Reply, title = "Auto Replies") { onNavigate("auto_replies"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.AutoAwesome, title = "AI Assistant") { onNavigate("ai_assistant"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.Group, title = "Postly Broadcast Lists") { onNavigate("broadcast"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.Campaign, title = "Drip Message Campaigns") { onNavigate("drip"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.Feed, title = "Message Templates") { onNavigate("templates"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.CalendarMonth, title = "Calendar") { onNavigate("calendar"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.PieChart, title = "Analytics") { onNavigate("analytics"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.Sync, title = "Sync Calendars") { onNavigate("sync_calendars"); onCloseDrawer() }

        Divider(color = Color(0xFFF1F5F9))

        // Section: Support & Community
        DrawerSectionHeader(title = "Support & Community")
        DrawerMenuItem(icon = Icons.Outlined.HeadsetMic, title = "Chat Support") { onNavigate("support"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.EmojiEvents, title = "Premium Chat Support") { onNavigate("premium_support"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.HelpOutline, title = "Help Center") { onNavigate("help"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.Public, title = "News Channel") { onNavigate("news"); onCloseDrawer() }

        Divider(color = Color(0xFFF1F5F9))

        // Section: Other
        DrawerSectionHeader(title = "Other")
        DrawerMenuItem(icon = Icons.Outlined.Settings, title = "Settings") { onNavigate("requirements"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.Language, title = "App Language") { onNavigate("language"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.Devices, title = "Web Tool") { onNavigate("web_tool"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.Info, title = "About") { onNavigate("about"); onCloseDrawer() }
        DrawerMenuItem(icon = Icons.Outlined.PowerSettingsNew, title = "Sign out") { onCloseDrawer() }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        color = TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun DrawerMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = TextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal
        )
    }
}
