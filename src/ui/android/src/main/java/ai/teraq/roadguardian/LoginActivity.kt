package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.*
import net.openid.appauth.*

/** 브라우저 인증 코드 흐름(AppAuth PKCE). 앱 안 로그인 WebView는 쓰지 않는다 */
class LoginActivity : Activity() {
    private lateinit var service: AuthorizationService
    private lateinit var status: TextView
    private lateinit var login: Button
    private lateinit var records: Button
    private lateinit var steps: LinearLayout
    private lateinit var ui: PhoneAccountUi
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var statusGeneration = 0L
    private var authBusy = false
    private var foreground = false
    private var connectionLease: AccountDisplayLease? = null
    private var connectionSession = ""
    private val watcher = android.os.Handler(android.os.Looper.getMainLooper())
    private val checkConnection = object : Runnable {
        override fun run() {
            if (connectionLease != null && (!connectionLease!!.valid(
                    System.currentTimeMillis(),
                    android.os.SystemClock.elapsedRealtime()
                ) ||
                        runCatching { AuthenticatedEventsClient(this@LoginActivity).sessionFingerprint() != connectionSession }.getOrDefault(
                            true
                        ))
            ) refreshConnection()
            watcher.postDelayed(this, 1000)
        }
    }
    private var expectedState: String? = null
    private var generation = 0L
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        service = AuthorizationService(this)
        expectedState = saved?.getString("expected_state")
        generation = saved?.getLong("generation") ?: 0L
        ui = PhoneAccountUi(this)
        val root = ui.install()
        ui.heading(root, "내 차량 계정 연결", "계정을 연결하면 내 차량의 사건 기록을 확인할 수 있습니다.")
        steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(steps)
        status = ui.notice("로그인은 브라우저에서 진행합니다.")
        login = ui.button("계정 연결", true) { begin() }
        root.addView(status)
        root.addView(login)
        records = ui.button("차량 사건 기록 보기") {
            startActivity(
                Intent(
                    this,
                    AccountEventsActivity::class.java
                )
            )
        }
        records.isEnabled = false
        root.addView(records)
        root.addView(ui.button("연결 상태 다시 확인") { if (!authBusy) refreshConnection() })
        root.addView(ui.button("이 기기의 로그인 해제") {
            generation++
            statusGeneration++
            expectedState = null
            authBusy = false
            login.isEnabled = true
            try {
                LoginStateStore(this).write(AuthState())
                refreshConnection()
                status.text = "이 기기의 로그인 정보를 지웠습니다. 브라우저 로그인은 유지될 수 있습니다."
            } catch (_: Exception) {
                status.text = "로그인 정보를 변경하지 못했습니다."
            }
        })
        root.addView(ui.button("돌아가기") { finish() })
        authBusy = expectedState != null
    }

    private fun flow(items: List<CaseFlowGraphic.Step>) {
        steps.removeAllViews()
        if (resources.configuration.fontScale >= 1.3f) items.forEach {
            steps.addView(
                ui.text(
                    "${it.label} · ${it.status}",
                    20f
                )
            )
        }
        else steps.addView(
            CaseFlowGraphic(this, items, true),
            LinearLayout.LayoutParams(-1, ui.dp(118))
        )
    }

    private fun configured(): Boolean = try {
        LoginConfiguration.validate(BuildConfig.OIDC_ISSUER, BuildConfig.OIDC_CLIENT_ID)
        BackendEndpoint.resolve(BuildConfig.AUTH_API_URL, "/api/capabilities", false)
        true
    } catch (_: Exception) {
        false
    }

    private fun refreshConnection() {
        val attempt = ++statusGeneration
        connectionLease = null
        connectionSession = ""
        records.isEnabled = false
        if (!configured()) {
            login.isEnabled = false
            flow(
                listOf(
                    CaseFlowGraphic.Step("서비스 설정", "준비 중", 3),
                    CaseFlowGraphic.Step("계정 연결", "대기", 0),
                    CaseFlowGraphic.Step("차량 조회", "대기", 0)
                )
            )
            status.text = "차량 기록 서비스가 아직 설정되지 않았습니다. 서비스 제공자의 연결 안내가 준비되면 계정을 연결할 수 있습니다."
            return
        }
        login.isEnabled = !authBusy
        flow(
            listOf(
                CaseFlowGraphic.Step("서비스 설정", "설정 있음", 1),
                CaseFlowGraphic.Step("계정 연결", "확인 중", 2),
                CaseFlowGraphic.Step("차량 조회", "확인 전", 0)
            )
        )
        status.text = "저장한 계정과 차량 조회 가능 여부를 확인하고 있습니다."
        worker.execute {
            val result = runCatching { AuthenticatedEventsClient(this).capabilitiesForDisplay() }
            runOnUiThread {
                if (isDestroyed || !foreground || authBusy || attempt != statusGeneration) return@runOnUiThread
                result.fold(
                    onSuccess = { received ->
                        if (!received.lease.valid(
                                System.currentTimeMillis(),
                                android.os.SystemClock.elapsedRealtime()
                            )
                        ) {
                            showIssue(AccountAccessException.Reason.EXPIRED)
                            return@runOnUiThread
                        }
                        val features = received.value
                        connectionLease = received.lease
                        connectionSession = received.sessionFingerprint
                        records.isEnabled = features.records
                        if (!features.known) {
                            flow(
                                listOf(
                                    CaseFlowGraphic.Step("서비스 정보", "미지원", 3),
                                    CaseFlowGraphic.Step("계정 정보", "기기 보관", 1),
                                    CaseFlowGraphic.Step("차량 조회", "목록에서 확인", 2)
                                )
                            )
                            status.text = "연결 상태 정보가 없는 서비스입니다. 사건 목록을 열어 조회 가능 여부를 확인해 주세요."
                        } else {
                            flow(
                                listOf(
                                    CaseFlowGraphic.Step("서비스 연결", "응답 확인", 1),
                                    CaseFlowGraphic.Step("계정 연결", "확인됨", 1),
                                    CaseFlowGraphic.Step(
                                        "차량 조회",
                                        if (features.vehicleAllowed) "허용됨" else "등록 확인",
                                        if (features.vehicleAllowed) 1 else 2
                                    )
                                )
                            )
                            status.text =
                                if (!features.vehicleAllowed) "계정은 확인됐지만 조회할 차량이 등록되지 않았습니다. 서비스 제공자의 차량 등록 안내를 확인해 주세요."
                                else if (BuildConfig.REVIEW_NOTES_ENABLED && features.notesRead && !features.notesWrite) "차량 사건과 저장한 메모를 볼 수 있습니다. 현재 계정의 메모 저장은 허용되지 않았습니다."
                                else "차량 사건 기록을 조회할 수 있습니다. 각 기록을 열 때 차량 권한을 다시 확인합니다."
                        }
                    },
                    onFailure = {
                        showIssue(
                            (it as? AccountAccessException)?.reason
                                ?: AccountAccessException.Reason.UNAVAILABLE
                        )
                    })
            }
        }
    }

    private fun showIssue(reason: AccountAccessException.Reason) {
        records.isEnabled = false
        val signIn = reason in setOf(
            AccountAccessException.Reason.SIGN_IN,
            AccountAccessException.Reason.EXPIRED,
            AccountAccessException.Reason.CHANGED
        )
        flow(
            listOf(
                CaseFlowGraphic.Step("서비스 설정", "설정 있음", 1),
                CaseFlowGraphic.Step(
                    "계정 연결",
                    if (signIn) "연결 필요" else "응답 확인 필요",
                    if (signIn) 2 else 3
                ),
                CaseFlowGraphic.Step("차량 조회", "미확인", 0)
            )
        )
        status.text =
            if (signIn) "계정을 연결하거나 다시 연결하면 차량 조회 가능 여부를 확인합니다." else AccountAccessException(reason).message
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (::status.isInitialized && !authBusy && !isFinishing) refreshConnection()
        watcher.post(checkConnection)
    }

    override fun onPause() {
        foreground = false
        statusGeneration++
        connectionLease = null
        connectionSession = ""
        watcher.removeCallbacks(checkConnection)
        if (::records.isInitialized) records.isEnabled = false
        super.onPause()
    }

    private fun begin() {
        if (!configured()) {
            refreshConnection()
            return
        }
        val attempt = ++generation
        connectionLease = null
        connectionSession = ""
        statusGeneration++
        authBusy = true
        records.isEnabled = false
        login.isEnabled = false
        status.text = "로그인 서버를 확인하고 있습니다."
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.OIDC_ISSUER)) { config, error ->
            if (isDestroyed || attempt != generation) return@fetchFromIssuer
            if (!foreground) {
                authBusy = false
                return@fetchFromIssuer
            }
            if (config == null || error != null) {
                authBusy = false
                status.text = "로그인 서버에 연결하지 못했습니다."
                login.isEnabled = true
                return@fetchFromIssuer
            }
            try {
                LoginConfiguration.secureEndpoint(config.authorizationEndpoint.toString())
                LoginConfiguration.secureEndpoint(config.tokenEndpoint.toString())
                check(config.discoveryDoc?.issuer?.toString() == BuildConfig.OIDC_ISSUER)
                val request = AuthorizationRequest.Builder(
                    config,
                    BuildConfig.OIDC_CLIENT_ID,
                    ResponseTypeValues.CODE,
                    Uri.parse(LoginConfiguration.REDIRECT)
                )
                    .setScope(
                        (listOf(
                            "openid",
                            "events:read"
                        ) + (if (BuildConfig.REVIEW_NOTES_ENABLED) listOf("events:review") else emptyList())
                                + (if (BuildConfig.SDK_OBSERVATIONS_ENABLED) listOf("events:observe") else emptyList())
                                + (if (BuildConfig.OWNER_CASES_ENABLED) listOf("events:create") else emptyList())
                                + (if (BuildConfig.CASE_PHOTOS_ENABLED) listOf("events:photos") else emptyList())
                                + (if (BuildConfig.POWERTRAIN_ENABLED) listOf("events:powertrain") else emptyList())
                                + (if (BuildConfig.MEASUREMENTS_ENABLED) listOf("events:measure") else emptyList())).joinToString(
                            " "
                        )
                    ).build()
                expectedState = request.state
                startActivityForResult(service.getAuthorizationRequestIntent(request), 301)
            } catch (_: Exception) {
                authBusy = false
                status.text = "로그인 설정이나 브라우저를 확인하세요."
                login.isEnabled = true
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("expected_state", expectedState)
        out.putLong("generation", generation)
        super.onSaveInstanceState(out)
    }

    @Deprecated("Legacy activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 301) return
        login.isEnabled = true
        val response = data?.let { AuthorizationResponse.fromIntent(it) }
        val error = data?.let { AuthorizationException.fromIntent(it) }
        val expected = expectedState
        expectedState = null
        if (resultCode != RESULT_OK || error != null || response == null || expected == null || response.state != expected) {
            authBusy = false
            status.text = "로그인이 취소됐거나 응답을 확인하지 못했습니다."
            return
        }
        login.isEnabled = false
        status.text = "로그인을 마무리하고 있습니다."
        val attempt = generation
        service.performTokenRequest(response.createTokenExchangeRequest()) { token, failure ->
            if (isDestroyed || attempt != generation) return@performTokenRequest
            authBusy = false
            login.isEnabled = true
            if (token == null || failure != null) {
                status.text = "로그인을 완료하지 못했습니다. 다시 시도하세요."
                return@performTokenRequest
            }
            try {
                val state = AuthState(response, null)
                state.update(token, null)
                check(state.isAuthorized)
                LoginStateStore(this).write(state)
                status.text = "로그인 정보를 이 기기에 보관했습니다."
                if (foreground) refreshConnection()
                val returnId = try {
                    AccountEventRoute.checked(intent.getStringExtra("return_event_id"))
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (returnId != null) {
                    startActivity(AccountEventNotifier.openIntent(this, returnId))
                    finish()
                }
            } catch (_: Exception) {
                status.text = "로그인 정보를 안전하게 저장하지 못했습니다."
            }
        }
    }

    override fun onDestroy() {
        watcher.removeCallbacks(checkConnection)
        worker.shutdownNow()
        if (::service.isInitialized) service.dispose()
        super.onDestroy()
    }
}
