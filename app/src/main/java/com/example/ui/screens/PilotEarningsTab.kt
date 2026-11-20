package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.OrderWithDetails
import com.example.data.model.PaymentMethod
import com.example.data.model.PilotBankAccountEntity
import com.example.data.model.PilotProfileEntity
import com.example.data.model.PilotWithdrawalEntity
import com.example.data.model.UserEntity
import com.example.data.model.WithdrawalMethod
import com.example.data.model.WithdrawalStatus
import com.example.ui.components.formatInr
import com.example.ui.theme.AlertRust
import com.example.ui.theme.AlertRustBg
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.HarvestAmber
import com.example.ui.theme.SoftSage
import com.example.ui.theme.VerifiedTeal
import com.example.ui.theme.VerifiedTealBg
import com.example.ui.viewmodel.WithdrawSheetUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PilotEarningsWalletTab(
    profile: PilotProfileEntity?,
    bankAccount: PilotBankAccountEntity?,
    withdrawals: List<PilotWithdrawalEntity>,
    completedMissions: List<OrderWithDetails>,
    onOpenWithdrawSheet: () -> Unit,
    onOpenWalletTopUp: (Double) -> Unit,
    onEditAccountUpi: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalNetEarnings = completedMissions.sumOf { it.order.pilotEarnings }
    val onlineEarnings = completedMissions
        .filter { it.paymentMethodEnum == PaymentMethod.ONLINE }
        .sumOf { it.order.pilotEarnings }
    val cashEarnings = completedMissions
        .filter { it.paymentMethodEnum == PaymentMethod.CASH }
        .sumOf { it.order.pilotEarnings }
    val totalWithdrawn = withdrawals
        .filter { it.status != WithdrawalStatus.FAILED.apiValue }
        .sumOf { it.amount }
    val availableToWithdraw = ((onlineEarnings - totalWithdrawn) * 100.0).roundToInt() / 100.0
    val safeAvailable = availableToWithdraw.coerceAtLeast(0.0)
    val dateFormatter = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.US) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Earnings & Instant Bank Withdrawal Hero Card
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = SoftSage,
                    border = BorderStroke(1.dp, ForestGreen.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Total Net Pilot Earnings (90%)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ForestGreen
                                )
                                Text(
                                    text = formatInr(totalNetEarnings),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ForestGreen,
                                    modifier = Modifier.testTag("pilot_total_earnings_text")
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.78f),
                                border = BorderStroke(1.dp, ForestGreen.copy(alpha = 0.25f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Star,
                                        contentDescription = null,
                                        tint = HarvestAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", profile?.ratingAverage ?: 5.0)} (${profile?.ratingCount ?: 0})",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = HarvestAmber
                                    )
                                }
                            }
                        }

                        // Withdrawable Bank Balance Highlight Box
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, ForestGreen.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Available to Withdraw",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = formatInr(safeAvailable),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = EmeraldPulse,
                                            modifier = Modifier.testTag("pilot_available_withdrawal_text")
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "Withdrawn",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = formatInr(totalWithdrawn),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = ForestGreen,
                                            modifier = Modifier.testTag("pilot_total_withdrawn_text")
                                        )
                                    }
                                }

                                val payoutSummary = when {
                                    bankAccount != null -> {
                                        val bankLabel = bankAccount.bankName.ifBlank { "Bank" }
                                        "Payout Bank: $bankLabel ${bankAccount.maskedAccountNumber()} (${bankAccount.ifscCode})"
                                    }
                                    !profile?.bankUpiId.isNullOrBlank() ->
                                        "Payout UPI: ${profile?.bankUpiId} (or add bank account)"
                                    else -> "Add your bank account or UPI during withdrawal"
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AccountBalance,
                                        contentDescription = null,
                                        tint = ForestGreen,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = payoutSummary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ForestGreen,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (cashEarnings > 0.0) {
                                    Text(
                                        text = "Online Payouts: ${formatInr(onlineEarnings)} • Cash Collected on Field: ${formatInr(cashEarnings)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Button(
                                    onClick = onOpenWithdrawSheet,
                                    enabled = safeAvailable >= 1.0,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("pilot_withdraw_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowOutward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (safeAvailable >= 1.0) {
                                            "Withdraw ${formatInr(safeAvailable)} to Bank"
                                        } else {
                                            "Withdraw to Bank"
                                        },
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }

                                if (safeAvailable < 1.0) {
                                    Text(
                                        text = when {
                                            completedMissions.isEmpty() ->
                                                "Complete an online-paid spray mission to withdraw earnings to your bank account."
                                            cashEarnings > 0.0 && onlineEarnings <= 0.0 ->
                                                "Cash on Field earnings are collected directly in cash. Online spray bookings can be withdrawn to your bank."
                                            else ->
                                                "All earned online payouts have already been withdrawn to your bank."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Completed Jobs: ${completedMissions.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ForestGreen
                            )
                            Text(
                                text = "90% Pilot Share",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = EmeraldPulse
                            )
                        }
                    }
                }
            }

            // Razorpay Commission Wallet Card
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Razorpay Commission Wallet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ForestGreen
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Balance: ${formatInr(profile?.walletBalance ?: 0.0)} • UPI: ${profile?.bankUpiId ?: "-"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = onEditAccountUpi,
                                modifier = Modifier.testTag("pilot_earnings_edit_upi_button")
                            ) {
                                Text("Edit Bank/UPI", color = ForestGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(250.0, 500.0, 1000.0).forEach { amt ->
                                OutlinedButton(
                                    onClick = { onOpenWalletTopUp(amt) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("pilot_topup_preset_${amt.toInt()}")
                                ) {
                                    Text("+${formatInr(amt)}", color = ForestGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Bank Withdrawal History
            if (withdrawals.isNotEmpty()) {
                item {
                    Text(
                        text = "Bank Withdrawals (${withdrawals.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ForestGreen,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(withdrawals, key = { it.id }) { withdrawal ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pilot_withdrawal_item_${withdrawal.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = VerifiedTealBg,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (withdrawal.methodEnum == WithdrawalMethod.BANK_TRANSFER) {
                                            Icons.Rounded.AccountBalance
                                        } else {
                                            Icons.Rounded.AccountBalanceWallet
                                        },
                                        contentDescription = null,
                                        tint = VerifiedTeal,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = withdrawal.destinationSummary(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Ref: ${withdrawal.referenceId} • ${dateFormatter.format(Date(withdrawal.createdAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatInr(withdrawal.amount),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ForestGreen
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = VerifiedTealBg
                                ) {
                                    Text(
                                        text = withdrawal.statusEnum.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = VerifiedTeal,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Completed Spray Jobs (${completedMissions.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ForestGreen,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (completedMissions.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No completed spray jobs yet. Accept and finish farm jobs to see payouts and farmer ratings here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            } else {
                items(completedMissions, key = { it.order.id }) { mission ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mission.order.locationAddress,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${mission.farmerName} • ${String.format(Locale.US, "%.2f", mission.order.acresToSpray)} ac • ${mission.paymentMethodEnum.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "+${formatInr(mission.order.pilotEarnings)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldPulse
                                )
                                val rating = mission.order.pilotRating
                                if (rating != null) {
                                    Text(
                                        text = "${rating}★ rated",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = HarvestAmber
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PilotWithdrawBottomSheet(
    user: UserEntity?,
    profile: PilotProfileEntity?,
    bankAccount: PilotBankAccountEntity?,
    availableBalance: Double,
    sheetState: WithdrawSheetUiState,
    onClearError: () -> Unit,
    onSubmitWithdrawal: (
        amountText: String,
        method: WithdrawalMethod,
        accountHolderName: String,
        bankAccountNumber: String,
        ifscCode: String,
        bankName: String,
        upiId: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultAmount = remember(availableBalance) {
        if (availableBalance % 1.0 == 0.0) {
            availableBalance.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", availableBalance)
        }
    }
    var amountText by rememberSaveable(availableBalance) { mutableStateOf(defaultAmount) }
    var selectedMethod by rememberSaveable { mutableStateOf(WithdrawalMethod.BANK_TRANSFER) }
    var holderName by rememberSaveable(bankAccount?.pilotId, user?.name) {
        mutableStateOf(bankAccount?.accountHolderName?.takeIf { it.isNotBlank() } ?: user?.name.orEmpty())
    }
    var accountNumber by rememberSaveable(bankAccount?.pilotId) {
        mutableStateOf(bankAccount?.accountNumber.orEmpty())
    }
    var ifscCode by rememberSaveable(bankAccount?.pilotId) {
        mutableStateOf(bankAccount?.ifscCode.orEmpty())
    }
    var bankName by rememberSaveable(bankAccount?.pilotId) {
        mutableStateOf(bankAccount?.bankName.orEmpty())
    }
    var upiId by rememberSaveable(profile?.userId) {
        mutableStateOf(profile?.bankUpiId.orEmpty())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Instant Bank Payout",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPulse
                    )
                    Text(
                        text = "Withdraw Earnings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SoftSage
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "Available",
                            style = MaterialTheme.typography.labelSmall,
                            color = ForestGreen
                        )
                        Text(
                            text = formatInr(availableBalance),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ForestGreen
                        )
                    }
                }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() || it == '.' }
                    amountText = filtered
                    onClearError()
                },
                label = { Text("Withdrawal Amount (₹)") },
                leadingIcon = {
                    Text(
                        text = "₹",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ForestGreen
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pilot_withdraw_amount_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val quickFractions = listOf(
                    "25%" to (availableBalance * 0.25),
                    "50%" to (availableBalance * 0.50),
                    "Full" to availableBalance
                )
                quickFractions.forEach { (label, rawAmt) ->
                    val rounded = ((rawAmt * 100.0).roundToInt() / 100.0).coerceAtLeast(1.0)
                    OutlinedButton(
                        onClick = {
                            amountText = if (rounded % 1.0 == 0.0) {
                                rounded.toInt().toString()
                            } else {
                                String.format(Locale.US, "%.2f", rounded)
                            }
                            onClearError()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = "$label (${formatInr(rounded)})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ForestGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Text(
                text = "Receive Money In",
                style = MaterialTheme.typography.labelLarge,
                color = ForestGreen
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WithdrawalMethod.entries.forEach { method ->
                    val active = selectedMethod == method
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selectedMethod = method
                                onClearError()
                            }
                            .border(
                                width = if (active) 1.5.dp else 1.dp,
                                color = if (active) ForestGreen else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .testTag("pilot_withdraw_method_${method.apiValue}"),
                        color = if (active) SoftSage else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (method == WithdrawalMethod.BANK_TRANSFER) {
                                    Icons.Rounded.AccountBalance
                                } else {
                                    Icons.Rounded.QrCode2
                                },
                                contentDescription = null,
                                tint = if (active) ForestGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = method.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) ForestGreen else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            if (selectedMethod == WithdrawalMethod.BANK_TRANSFER) {
                OutlinedTextField(
                    value = holderName,
                    onValueChange = {
                        holderName = it
                        onClearError()
                    },
                    label = { Text("Account Holder Name") },
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pilot_withdraw_holder_input")
                )

                OutlinedTextField(
                    value = accountNumber,
                    onValueChange = { input ->
                        accountNumber = input.filter(Char::isDigit).take(18)
                        onClearError()
                    },
                    label = { Text("Bank Account Number") },
                    leadingIcon = { Icon(Icons.Rounded.Numbers, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pilot_withdraw_account_input")
                )

                OutlinedTextField(
                    value = ifscCode,
                    onValueChange = { input ->
                        ifscCode = input.uppercase(Locale.US).filter { it.isLetterOrDigit() }.take(11)
                        onClearError()
                    },
                    label = { Text("IFSC Code") },
                    placeholder = { Text("e.g. HDFC0001234") },
                    leadingIcon = { Icon(Icons.Rounded.AccountBalance, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pilot_withdraw_ifsc_input")
                )

                OutlinedTextField(
                    value = bankName,
                    onValueChange = {
                        bankName = it
                        onClearError()
                    },
                    label = { Text("Bank Name (Optional)") },
                    placeholder = { Text("e.g. State Bank of India") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pilot_withdraw_bank_name_input")
                )
            } else {
                OutlinedTextField(
                    value = upiId,
                    onValueChange = {
                        upiId = it
                        onClearError()
                    },
                    label = { Text("Bank UPI ID") },
                    placeholder = { Text("e.g. pilotname@okaxis") },
                    leadingIcon = { Icon(Icons.Rounded.QrCode2, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pilot_withdraw_upi_input")
                )
            }

            val err = sheetState.errorMessage
            if (!err.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AlertRustBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = AlertRust,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            val parsedPreview = amountText.toDoubleOrNull()
            Button(
                onClick = {
                    onSubmitWithdrawal(
                        amountText,
                        selectedMethod,
                        holderName,
                        accountNumber,
                        ifscCode,
                        bankName,
                        upiId
                    )
                },
                enabled = !sheetState.isSubmitting,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("pilot_confirm_withdraw_button")
            ) {
                if (sheetState.isSubmitting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    val amtLabel = if (parsedPreview != null && parsedPreview >= 1.0) {
                        formatInr(parsedPreview)
                    } else {
                        "Earnings"
                    }
                    Text(
                        text = if (selectedMethod == WithdrawalMethod.BANK_TRANSFER) {
                            "Withdraw $amtLabel to Bank"
                        } else {
                            "Withdraw $amtLabel via UPI"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
