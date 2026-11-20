package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.OrderStatus
import com.example.data.model.PaymentMethod
import com.example.data.model.UserRole
import com.example.data.model.WithdrawalStatus
import com.example.data.remote.KrishivantriRuntimeConfig
import com.example.ui.components.InsufficientWalletDialog
import com.example.ui.components.RazorpayCheckoutSheet
import com.example.ui.screens.AdminDashboardScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.PilotDashboardScreen
import com.example.ui.screens.PilotWithdrawBottomSheet
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.KrishivantriTheme
import com.example.ui.viewmodel.KrishivantriViewModel
import com.example.ui.viewmodel.RazorpaySheetState
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject
import org.maplibre.android.MapLibre
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), PaymentResultWithDataListener {

    private val viewModel: KrishivantriViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KrishivantriRuntimeConfig.configure(
            apiBaseUrl = BuildConfig.API_BASE_URL,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
            razorpayKeyId = BuildConfig.RAZORPAY_KEY_ID,
            satelliteTileUrl = BuildConfig.SATELLITE_TILE_URL,
            satelliteApiKey = BuildConfig.SATELLITE_API_KEY,
            isDebugLogging = BuildConfig.DEBUG
        )
        runCatching { MapLibre.getInstance(applicationContext) }
        enableEdgeToEdge()
        setContent {
            KrishivantriTheme {
                KrishivantriPilotApp(
                    viewModel = viewModel,
                    onLaunchRazorpay = ::launchRazorpayCheckout
                )
            }
        }
    }

    private fun launchRazorpayCheckout(sheet: RazorpaySheetState) {
        if (!KrishivantriRuntimeConfig.isRazorpayConfigured()) {
            viewModel.onRazorpayKeyMissing()
            return
        }
        runCatching {
            val checkout = Checkout()
            checkout.setKeyID(KrishivantriRuntimeConfig.razorpayKeyId.trim())

            val options = JSONObject().apply {
                put("name", "Krishivantri Drone Pilot")
                put("description", sheet.subtitle)
                put("currency", "INR")
                put("amount", (sheet.amount * 100.0).roundToInt().coerceAtLeast(100))
                put("theme", JSONObject().put("color", "#1B4332"))

                val retryObj = JSONObject().apply {
                    put("enabled", true)
                    put("max_count", 3)
                }
                put("retry", retryObj)

                val prefill = JSONObject()
                viewModel.currentUser.value?.phone?.takeIf { it.isNotBlank() }?.let {
                    prefill.put("contact", it)
                }
                viewModel.currentUser.value?.email?.takeIf { it.isNotBlank() }?.let {
                    prefill.put("email", it)
                }
                put("prefill", prefill)
            }
            checkout.open(this, options)
        }.onFailure { err ->
            viewModel.onRazorpayPaymentError(-1, err.localizedMessage)
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        viewModel.confirmRazorpayPayment(razorpayPaymentId ?: paymentData?.paymentId)
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        viewModel.onRazorpayPaymentError(code, response)
    }
}

@Composable
fun KrishivantriPilotApp(
    viewModel: KrishivantriViewModel,
    onLaunchRazorpay: (RazorpaySheetState) -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val mapState by viewModel.mapBookingState.collectAsStateWithLifecycle()
    val pilotProfile by viewModel.pilotProfile.collectAsStateWithLifecycle()
    val pilotQueueOrders by viewModel.pilotQueueOrders.collectAsStateWithLifecycle()
    val pilotAssignedOrders by viewModel.pilotAssignedOrders.collectAsStateWithLifecycle()
    val pilotBankAccount by viewModel.pilotBankAccount.collectAsStateWithLifecycle()
    val pilotWithdrawals by viewModel.pilotWithdrawals.collectAsStateWithLifecycle()
    val realtimeOperators by viewModel.realtimeOperators.collectAsStateWithLifecycle()
    val adminOverview by viewModel.adminOverview.collectAsStateWithLifecycle()
    val razorpaySheet by viewModel.razorpaySheet.collectAsStateWithLifecycle()
    val insufficientWalletAlert by viewModel.insufficientWalletAlert.collectAsStateWithLifecycle()
    val withdrawSheetState by viewModel.withdrawSheetState.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.selectAuthRole(UserRole.PILOT)
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val user = currentUser
            if (user == null) {
                if (authState.isRestoringSession) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = ForestGreen)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Restoring your NexusTekFusion pilot session...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    AuthScreen(
                        state = authState,
                        onToggleSignUp = viewModel::toggleAuthMode,
                        onUpdateLoginEmail = viewModel::updateLoginEmail,
                        onUpdateOtpInput = viewModel::updateOtpInput,
                        onRequestOtp = { viewModel.requestPilotLoginOtp() },
                        onVerifyOtp = { viewModel.verifyPilotLoginOtp() },
                        onChangeLoginEmail = viewModel::changeLoginEmail,
                        onSubmitPilot = viewModel::submitPilotAuth
                    )
                }
            } else {
                when (UserRole.fromApi(user.role)) {
                    UserRole.ADMIN -> AdminDashboardScreen(
                        overview = adminOverview,
                        onVerifyPilotLicense = viewModel::verifyPilotLicense,
                        onSignOut = viewModel::signOut
                    )

                    UserRole.PILOT, UserRole.FARMER -> PilotDashboardScreen(
                        user = user,
                        profile = pilotProfile,
                        bankAccount = pilotBankAccount,
                        withdrawals = pilotWithdrawals,
                        mapRepository = viewModel.mapRepository,
                        mapState = mapState,
                        queueOrders = pilotQueueOrders,
                        assignedOrders = pilotAssignedOrders,
                        realtimeOperators = realtimeOperators,
                        onLocationPermissionResult = viewModel::onLocationPermissionResult,
                        onResumeGpsUpdates = viewModel::startBatteryEfficientGpsUpdates,
                        onPauseGpsUpdates = viewModel::stopBatteryEfficientGpsUpdates,
                        onRefreshCurrentLocation = viewModel::refreshCurrentLocation,
                        onRefreshPilotSync = viewModel::refreshPilotSync,
                        onSelectMapStyle = viewModel::selectMapStyle,
                        onMapCameraMoved = viewModel::onMapCameraMoved,
                        onMapLoadingChanged = viewModel::onMapLoadingChanged,
                        onMapError = viewModel::onMapError,
                        onRetryMapLoad = viewModel::retryMapLoad,
                        onZoomMap = viewModel::zoomMap,
                        onToggleOnline = viewModel::togglePilotOnline,
                        onAcceptOrder = viewModel::acceptOrder,
                        onRejectOrder = viewModel::rejectOrder,
                        onStartSpraying = viewModel::startSpraying,
                        onCompleteOrder = viewModel::completeOrder,
                        onOpenWalletTopUp = viewModel::openWalletTopUp,
                        onOpenWithdrawSheet = viewModel::openWithdrawSheet,
                        onUpdateAccountDetails = viewModel::updatePilotAccountDetails,
                        onSaveBankAccount = viewModel::savePilotBankAccount,
                        onSignOut = viewModel::signOut,
                        onSilentSync = viewModel::syncPilotSilently
                    )
                }
            }
        }
    }

    razorpaySheet?.let { sheet ->
        RazorpayCheckoutSheet(
            state = sheet,
            onConfirmPayment = { onLaunchRazorpay(sheet) },
            onDismiss = viewModel::dismissRazorpaySheet
        )
    }

    insufficientWalletAlert?.let { alert ->
        InsufficientWalletDialog(
            alert = alert,
            onTopUpNow = { recommendedAmount -> viewModel.openWalletTopUp(recommendedAmount) },
            onDismiss = viewModel::dismissInsufficientWalletAlert
        )
    }

    if (withdrawSheetState.isVisible) {
        val onlineEarnings = pilotAssignedOrders
            .filter {
                it.statusEnum == OrderStatus.COMPLETED &&
                    it.paymentMethodEnum == PaymentMethod.ONLINE
            }
            .sumOf { it.order.pilotEarnings }
        val totalWithdrawn = pilotWithdrawals
            .filter { it.status != WithdrawalStatus.FAILED.apiValue }
            .sumOf { it.amount }
        val availableToWithdraw = ((onlineEarnings - totalWithdrawn) * 100.0).roundToInt() / 100.0

        PilotWithdrawBottomSheet(
            user = currentUser,
            profile = pilotProfile,
            bankAccount = pilotBankAccount,
            availableBalance = availableToWithdraw.coerceAtLeast(0.0),
            sheetState = withdrawSheetState,
            onClearError = viewModel::clearWithdrawError,
            onSubmitWithdrawal = viewModel::submitPilotWithdrawal,
            onDismiss = viewModel::dismissWithdrawSheet
        )
    }
}
