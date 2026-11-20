package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DroneType
import com.example.shared.R as SharedR
import com.example.ui.components.KrishivantriBrandMark
import com.example.ui.theme.AlertRust
import com.example.ui.theme.AlertRustBg
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.VerifiedTeal
import com.example.ui.theme.VerifiedTealBg
import com.example.ui.viewmodel.AuthUiState
import java.util.Locale

/**
 * Dedicated Drone Pilot Registration & Email OTP Authentication screen for the
 * NexusTekFusion Krishivantri Drone Pilot app.
 */
@Composable
fun AuthScreen(
    state: AuthUiState,
    onToggleSignUp: (Boolean) -> Unit,
    onUpdateLoginEmail: (String) -> Unit,
    onUpdateOtpInput: (String) -> Unit,
    onRequestOtp: () -> Unit,
    onVerifyOtp: () -> Unit,
    onChangeLoginEmail: () -> Unit,
    onSubmitPilot: (
        name: String,
        email: String,
        phone: String,
        droneType: DroneType,
        tankCapacityText: String,
        licenseUri: String,
        bankUpiId: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable { mutableStateOf("") }
    var regEmail by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var selectedDroneType by rememberSaveable { mutableStateOf(DroneType.QUADCOPTER) }
    var tankCapacityText by rememberSaveable { mutableStateOf("10") }
    var bankUpiId by rememberSaveable { mutableStateOf("") }
    var licenseUri by rememberSaveable { mutableStateOf("") }

    val licensePhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            licenseUri = uri.toString()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            KrishivantriBrandMark(
                title = "Krishivantri Drone Pilot",
                subtitle = "NexusTekFusion • Partner Dispatch & Telemetry"
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.height(172.dp)) {
                    Image(
                        painter = painterResource(id = SharedR.drawable.img_krishivantri_hero_1794983437632),
                        contentDescription = "Agricultural spraying drone flying above crop fields",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        ForestGreen.copy(alpha = 0.92f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(18.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FlightTakeoff,
                                contentDescription = null,
                                tint = SoftSage,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "NEXUSTEKFUSION PILOT PARTNER",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = SoftSage
                            )
                        }
                        Text(
                            text = "Fly Missions & Earn 90% Payouts",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Receive live field polygons from Krishivantri farmers, share real-time GPS ETA, and manage Razorpay wallet reserves.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SoftSage
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(4.dp)
                    ) {
                        PilotAuthTab(
                            label = "New Pilot Registration",
                            selected = state.isSignUpMode,
                            onClick = { onToggleSignUp(true) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pilot_auth_tab_signup")
                        )
                        PilotAuthTab(
                            label = "Existing Pilot",
                            selected = !state.isSignUpMode,
                            onClick = { onToggleSignUp(false) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pilot_auth_tab_login")
                        )
                    }

                    if (state.isSignUpMode) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Pilot Full Name *") },
                            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pilot_auth_name_input")
                        )

                        OutlinedTextField(
                            value = regEmail,
                            onValueChange = { regEmail = it.trim() },
                            label = { Text("Email Address *") },
                            placeholder = { Text("pilot@nexustekfusion.com") },
                            leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pilot_auth_reg_email_input")
                        )

                        OutlinedTextField(
                            value = phone,
                            onValueChange = { input ->
                                phone = input.filter(Char::isDigit).take(15)
                            },
                            label = { Text("Pilot Mobile Number *") },
                            leadingIcon = { Icon(Icons.Rounded.Phone, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pilot_auth_phone_input")
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Drone Platform",
                                style = MaterialTheme.typography.labelLarge,
                                color = ForestGreen
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                DroneType.entries.forEach { drone ->
                                    val active = selectedDroneType == drone
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { selectedDroneType = drone }
                                            .border(
                                                width = if (active) 1.5.dp else 1.dp,
                                                color = if (active) ForestGreen else MaterialTheme.colorScheme.outline,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .testTag("pilot_drone_${drone.apiValue}"),
                                        color = if (active) SoftSage else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = drone.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                                color = if (active) ForestGreen else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = tankCapacityText,
                                onValueChange = { tankCapacityText = it },
                                label = { Text("Spray Tank Capacity (Liters)") },
                                leadingIcon = { Icon(Icons.Rounded.WaterDrop, contentDescription = null) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("pilot_tank_capacity_input")
                            )

                            OutlinedTextField(
                                value = bankUpiId,
                                onValueChange = { bankUpiId = it },
                                label = { Text("Bank UPI ID (for 90% payouts)") },
                                placeholder = { Text("e.g. pilotname@okaxis") },
                                leadingIcon = { Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("pilot_upi_input")
                            )

                            // Zero-permission Android Photo Picker for DGCA Remote Pilot License upload
                            OutlinedButton(
                                onClick = {
                                    licensePhotoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (licenseUri.isNotBlank()) EmeraldPulse else MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("pilot_license_upload_button")
                            ) {
                                Icon(
                                    imageVector = if (licenseUri.isNotBlank()) {
                                        Icons.Rounded.CheckCircle
                                    } else {
                                        Icons.Rounded.UploadFile
                                    },
                                    contentDescription = "Upload pilot license image",
                                    tint = if (licenseUri.isNotBlank()) EmeraldPulse else ForestGreen
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (licenseUri.isNotBlank()) {
                                        "DGCA License Selected"
                                    } else {
                                        "Upload Drone Pilot License Photo"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = ForestGreen
                                )
                            }
                        }

                        AuthErrorBanner(state.errorMessage)

                        Button(
                            onClick = {
                                onSubmitPilot(
                                    name,
                                    regEmail,
                                    phone,
                                    selectedDroneType,
                                    tankCapacityText,
                                    licenseUri,
                                    bankUpiId
                                )
                            },
                            enabled = !state.isLoading,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("pilot_auth_submit_button")
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = "Register Pilot & Start Dispatch",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    } else if (!state.isOtpStep) {
                        // Existing Pilot Login — Step 1: Enter Email Address & Get OTP
                        Text(
                            text = "Sign in securely with a one-time verification code sent to your registered email address.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = state.emailInput,
                            onValueChange = onUpdateLoginEmail,
                            label = { Text("Registered Email Address") },
                            placeholder = { Text("pilot@nexustekfusion.com") },
                            leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pilot_auth_login_email_input")
                        )

                        AuthErrorBanner(state.errorMessage)

                        Button(
                            onClick = onRequestOtp,
                            enabled = !state.isLoading && state.resendCooldownSeconds == 0,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("pilot_auth_get_otp_button")
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = if (state.resendCooldownSeconds > 0) {
                                        "Wait ${state.resendCooldownSeconds}s for Get OTP"
                                    } else {
                                        "Get OTP"
                                    },
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    } else {
                        // Existing Pilot Login — Step 2: Verify Email OTP
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = VerifiedTealBg,
                            border = BorderStroke(1.dp, VerifiedTeal.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MarkEmailRead,
                                        contentDescription = null,
                                        tint = VerifiedTeal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = state.emailInput,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = ForestGreen,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(
                                        onClick = onChangeLoginEmail,
                                        modifier = Modifier.testTag("pilot_auth_change_email_button")
                                    ) {
                                        Text("Change", color = VerifiedTeal, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Text(
                                    text = state.otpNoticeMessage
                                        ?: "Enter the 6-digit OTP sent from NexusTekFusion.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ForestGreen
                                )
                                val expiryMins = state.otpExpiresRemainingSeconds / 60
                                val expirySecs = state.otpExpiresRemainingSeconds % 60
                                val expiryFormatted = String.format(Locale.US, "%d:%02d", expiryMins, expirySecs)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LockClock,
                                        contentDescription = null,
                                        tint = if (state.otpExpiresRemainingSeconds > 0) VerifiedTeal else AlertRust,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = if (state.otpExpiresRemainingSeconds > 0) {
                                            "OTP expires in $expiryFormatted"
                                        } else {
                                            "OTP expired — tap Resend OTP"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (state.otpExpiresRemainingSeconds > 0) VerifiedTeal else AlertRust
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = state.otpInput,
                            onValueChange = onUpdateOtpInput,
                            label = { Text("Enter Email OTP") },
                            placeholder = { Text("6-digit verification code") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pilot_auth_otp_input")
                        )

                        AuthErrorBanner(state.errorMessage)

                        Button(
                            onClick = onVerifyOtp,
                            enabled = !state.isLoading &&
                                state.otpInput.length >= 6 &&
                                state.otpExpiresRemainingSeconds > 0 &&
                                state.failedOtpAttempts < state.maxOtpAttempts,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("pilot_auth_verify_otp_button")
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = "Verify OTP & Log In",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onRequestOtp,
                            enabled = !state.isLoading && state.resendCooldownSeconds == 0,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("pilot_auth_resend_otp_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = ForestGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (state.resendCooldownSeconds > 0) {
                                    "Resend OTP in ${state.resendCooldownSeconds}s"
                                } else {
                                    "Resend OTP"
                                },
                                color = ForestGreen,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Text(
                        text = "Looking to book spraying for your farm? Use the Krishivantri Farmer app.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "NexusTekFusion • Krishivantri Drone Pilot Operations",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun AuthErrorBanner(errorMsg: String?) {
    if (!errorMsg.isNullOrBlank()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AlertRustBg,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pilot_auth_error_banner")
        ) {
            Text(
                text = errorMsg,
                style = MaterialTheme.typography.bodySmall,
                color = AlertRust,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun PilotAuthTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        tonalElevation = if (selected) 1.dp else 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) ForestGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
