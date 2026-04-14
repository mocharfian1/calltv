package com.mocharfian.tvphone

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private val keypadRows = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#"),
)

@Composable
fun TvPhoneApp(viewModel: DialerViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTvDevice = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    val isAudioPermissionGranted = rememberAudioPermissionState()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onAudioPermissionChanged(granted)
    }

    LaunchedEffect(isAudioPermissionGranted) {
        viewModel.onAudioPermissionChanged(isAudioPermissionGranted)
    }

    val expandedLayout = isTvDevice || configuration.screenWidthDp >= 900

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF70E1C8),
            secondary = Color(0xFFFFC857),
            tertiary = Color(0xFFFF8A65),
            background = Color(0xFF08131A),
            surface = Color(0xFF10212B),
            surfaceVariant = Color(0xFF173240),
            onPrimary = Color(0xFF04201B),
            onSecondary = Color(0xFF2F2100),
            onBackground = Color(0xFFF6FBFF),
            onSurface = Color(0xFFF6FBFF),
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF061017),
                                Color(0xFF10222F),
                                Color(0xFF0F1723),
                            ),
                        ),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = if (expandedLayout) 40.dp else 18.dp,
                            vertical = if (expandedLayout) 28.dp else 18.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    HeaderBlock(uiState = uiState)
                    ServerCard(
                        uiState = uiState,
                        onUrlChange = viewModel::updateServerUrlInput,
                        onConnect = viewModel::connectToServer,
                        onDisconnect = viewModel::disconnectFromServer,
                    )
                    if (!uiState.isAudioPermissionGranted) {
                        PermissionCard(
                            onGrant = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        )
                    }

                    if (expandedLayout) {
                        ExpandedLayout(
                            uiState = uiState,
                            onDigit = viewModel::appendDigit,
                            onDelete = viewModel::deleteLastDigit,
                            onClear = viewModel::clearNumber,
                            onCall = viewModel::startCall,
                            onEnd = viewModel::endCall,
                            onAccept = viewModel::acceptIncoming,
                            onReject = viewModel::rejectIncoming,
                            onReuseNumber = viewModel::reuseNumber,
                        )
                    } else {
                        CompactLayout(
                            uiState = uiState,
                            onDigit = viewModel::appendDigit,
                            onDelete = viewModel::deleteLastDigit,
                            onClear = viewModel::clearNumber,
                            onCall = viewModel::startCall,
                            onEnd = viewModel::endCall,
                            onAccept = viewModel::acceptIncoming,
                            onReject = viewModel::rejectIncoming,
                            onReuseNumber = viewModel::reuseNumber,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandedLayout(
    uiState: DialerUiState,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onCall: () -> Unit,
    onEnd: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onReuseNumber: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        DialPadPanel(
            modifier = Modifier.weight(1.15f),
            uiState = uiState,
            onDigit = onDigit,
            onDelete = onDelete,
            onClear = onClear,
            onCall = onCall,
            onEnd = onEnd,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SelfDeviceCard(uiState = uiState)
            IncomingCallCard(uiState = uiState, onAccept = onAccept, onReject = onReject)
            StatusCard(uiState = uiState)
            OnlineDevicesCard(uiState = uiState, onReuseNumber = onReuseNumber)
            RecentCallsCard(uiState = uiState, onReuseNumber = onReuseNumber)
            FootnoteCard()
        }
    }
}

@Composable
private fun CompactLayout(
    uiState: DialerUiState,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onCall: () -> Unit,
    onEnd: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onReuseNumber: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SelfDeviceCard(uiState = uiState)
        IncomingCallCard(uiState = uiState, onAccept = onAccept, onReject = onReject)
        StatusCard(uiState = uiState)
        DialPadPanel(
            uiState = uiState,
            onDigit = onDigit,
            onDelete = onDelete,
            onClear = onClear,
            onCall = onCall,
            onEnd = onEnd,
        )
        OnlineDevicesCard(uiState = uiState, onReuseNumber = onReuseNumber)
        RecentCallsCard(uiState = uiState, onReuseNumber = onReuseNumber)
        FootnoteCard()
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun HeaderBlock(uiState: DialerUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "TV Phone Internet Call",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "HP dan Android TV bisa saling menelepon lewat internet selama keduanya sama-sama online di server.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
        StatusBadge(
            label = when {
                !uiState.isConnected -> "Offline"
                uiState.callPhase == CallPhase.Idle -> "Online"
                uiState.callPhase == CallPhase.Dialing -> "Calling"
                uiState.callPhase == CallPhase.Incoming -> "Incoming"
                else -> "Connected"
            },
            accent = when {
                !uiState.isConnected -> Color(0xFFB33A3A)
                uiState.callPhase == CallPhase.Idle -> MaterialTheme.colorScheme.primary
                uiState.callPhase == CallPhase.Dialing -> MaterialTheme.colorScheme.tertiary
                uiState.callPhase == CallPhase.Incoming -> Color(0xFF8BC34A)
                else -> MaterialTheme.colorScheme.secondary
            },
        )
    }
}

@Composable
private fun ServerCard(
    uiState: DialerUiState,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Server Internet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = uiState.serverUrlInput,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Alamat WebSocket Server") },
                placeholder = { Text("ws://IP-SERVER:8080") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    label = if (uiState.isConnected) "Reconnect" else "Connect",
                    tone = ButtonTone.Positive,
                    enabled = uiState.serverUrlInput.isNotBlank(),
                    onClick = onConnect,
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    label = "Disconnect",
                    tone = ButtonTone.Neutral,
                    enabled = uiState.isConnected,
                    onClick = onDisconnect,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3D2C11)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Akses mikrofon dibutuhkan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Tanpa izin mikrofon, perangkat bisa online tetapi tidak bisa saling bicara.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            ActionButton(
                label = "Izinkan",
                tone = ButtonTone.Positive,
                enabled = true,
                onClick = onGrant,
            )
        }
    }
}

@Composable
private fun SelfDeviceCard(uiState: DialerUiState) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Perangkat Ini",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = uiState.selfDevice.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(
                    label = "Kode ${uiState.selfDevice.code}",
                    accent = MaterialTheme.colorScheme.primary,
                )
                StatusBadge(
                    label = uiState.selfDevice.platform.label,
                    accent = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun IncomingCallCard(
    uiState: DialerUiState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    if (uiState.callPhase != CallPhase.Incoming || uiState.incomingPeer == null) return

    val peer = uiState.incomingPeer
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF17341E)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Panggilan Masuk",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${peer.name} • ${peer.platform.label}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "Kode ${peer.code}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    label = "Jawab",
                    tone = ButtonTone.Positive,
                    enabled = true,
                    onClick = onAccept,
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    label = "Tolak",
                    tone = ButtonTone.Danger,
                    enabled = true,
                    onClick = onReject,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: DialerUiState) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when {
                    uiState.callPhase == CallPhase.Incoming && uiState.incomingPeer != null -> "${uiState.incomingPeer.name} sedang memanggil."
                    uiState.callPhase != CallPhase.Idle && uiState.activePeer != null -> "${uiState.activePeer.name} • ${uiState.activePeer.platform.label}"
                    uiState.isConnected -> "Terhubung ke server."
                    else -> "Belum terhubung ke server."
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = uiState.helperText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            )
            if (uiState.callPhase == CallPhase.Connected) {
                StatusBadge(
                    label = "Durasi ${formatElapsed(uiState.elapsedSeconds)}",
                    accent = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun OnlineDevicesCard(
    uiState: DialerUiState,
    onReuseNumber: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Perangkat Online",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (uiState.discoveredDevices.isEmpty()) {
                Text(
                    text = "Belum ada perangkat online lain di server. Pastikan HP dan TV sama-sama connect ke server yang sama.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                )
            } else {
                uiState.discoveredDevices.forEach { peer ->
                    DeviceButton(
                        peer = peer,
                        enabled = uiState.callPhase == CallPhase.Idle,
                        onClick = { onReuseNumber(peer.code) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentCallsCard(
    uiState: DialerUiState,
    onReuseNumber: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Riwayat Panggilan",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (uiState.recentCalls.isEmpty()) {
                Text(
                    text = "Riwayat akan muncul setelah panggilan pertama berhasil atau gagal.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                )
            } else {
                uiState.recentCalls.forEach { call ->
                    HistoryButton(
                        recentCall = call,
                        enabled = uiState.callPhase == CallPhase.Idle,
                        onClick = { onReuseNumber(call.number) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FootnoteCard() {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF132A1D)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Mode Saat Ini",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Versi ini memakai server WebSocket untuk registry perangkat, signaling, dan relay audio. Keduanya harus membuka aplikasi dan connect ke server yang sama.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun DialPadPanel(
    modifier: Modifier = Modifier,
    uiState: DialerUiState,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onCall: () -> Unit,
    onEnd: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            NumberDisplay(uiState = uiState)
            keypadRows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { digit ->
                        KeypadButton(
                            modifier = Modifier.weight(1f),
                            mainLabel = digit,
                            enabled = uiState.callPhase == CallPhase.Idle,
                            onClick = { onDigit(digit) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    label = "Delete",
                    tone = ButtonTone.Neutral,
                    enabled = uiState.callPhase == CallPhase.Idle && uiState.enteredNumber.isNotBlank(),
                    onClick = onDelete,
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    label = "Clear",
                    tone = ButtonTone.Warning,
                    enabled = uiState.callPhase == CallPhase.Idle && uiState.enteredNumber.isNotBlank(),
                    onClick = onClear,
                )
            }
            ActionButton(
                modifier = Modifier.fillMaxWidth(),
                label = if (uiState.callPhase == CallPhase.Idle) "Call" else "End Call",
                tone = if (uiState.callPhase == CallPhase.Idle) ButtonTone.Positive else ButtonTone.Danger,
                enabled = if (uiState.callPhase == CallPhase.Idle) {
                    uiState.enteredNumber.isNotBlank()
                } else {
                    true
                },
                onClick = {
                    if (uiState.callPhase == CallPhase.Idle) {
                        onCall()
                    } else {
                        onEnd()
                    }
                },
            )
        }
    }
}

@Composable
private fun NumberDisplay(uiState: DialerUiState) {
    val shownNumber = when {
        uiState.callPhase == CallPhase.Idle && uiState.enteredNumber.isBlank() -> "Masukkan kode 6 digit"
        uiState.callPhase == CallPhase.Idle -> uiState.enteredNumber
        uiState.activePeer != null -> "${uiState.activePeer.code} • ${uiState.activePeer.name}"
        else -> "Menyiapkan panggilan"
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Kode Tujuan",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
            )
            Text(
                text = shownNumber,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceButton(
    peer: PeerDevice,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 86.dp)
            .graphicsLayer {
                val scale = if (focused) 1.02f else 1f
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            },
            contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = peer.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = peer.platform.label,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "Kode ${peer.code}",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun KeypadButton(
    modifier: Modifier = Modifier,
    mainLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .height(88.dp)
            .graphicsLayer {
                val scale = if (focused) 1.04f else 1f
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
            },
            contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        ),
    ) {
        Text(
            text = mainLabel,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

private enum class ButtonTone {
    Neutral,
    Positive,
    Warning,
    Danger,
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    label: String,
    tone: ButtonTone,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val baseColor = when (tone) {
        ButtonTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
        ButtonTone.Positive -> Color(0xFF1B8F6A)
        ButtonTone.Warning -> Color(0xFF9C6A0C)
        ButtonTone.Danger -> Color(0xFFB33A3A)
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .height(78.dp)
            .graphicsLayer {
                val scale = if (focused) 1.03f else 1f
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) baseColor.copy(alpha = 0.88f) else baseColor,
            contentColor = Color.White,
            disabledContainerColor = baseColor.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.4f),
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HistoryButton(
    recentCall: RecentCall,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 90.dp)
            .graphicsLayer {
                val scale = if (focused) 1.02f else 1f
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.92f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            },
            contentColor = if (focused) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = recentCall.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = recentCall.timeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "Kode ${recentCall.number} • ${recentCall.platformLabel}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = recentCall.status,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = recentCall.durationLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.18f),
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = 0.08f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(accent),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun rememberAudioPermissionState(): Boolean {
    val context = LocalContext.current
    return remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
