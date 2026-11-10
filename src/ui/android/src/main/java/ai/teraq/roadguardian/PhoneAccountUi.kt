package ai.teraq.roadguardian

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.view.View
import android.widget.*

/** 휴대폰 계정 화면 공통 레이아웃. 글자 크기 조절, 시스템 여백, 스크롤 버튼 */
class PhoneAccountUi(private val activity: Activity, val light: Boolean = true) {
    val context: android.content.Context get() = activity
    val ink = PleosPalette.ink(light)
    private val blue = PleosPalette.action(light)
    val muted = PleosPalette.muted(light)
    val evidenceCaptionSize: Float get() = if (activity.resources.configuration.screenWidthDp >= 760) 30f else 22f
    val cardColor = PleosPalette.panel(light)
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    fun text(value: String, size: Float = 18f, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(ink)
        setLineSpacing(dp(4).toFloat(), 1f)
        typeface = PleosTypography.face(bold)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }

    fun install(): LinearLayout {
        val backgroundColor = PleosPalette.background(light)
        activity.window.statusBarColor = backgroundColor
        activity.window.navigationBarColor = backgroundColor
        activity.window.decorView.systemUiVisibility =
            if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)
            addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
                insets
            }
        }
        // 여백이 늦게 들어오면 첫 포커스가 긴 본문을 제목 아래로 스크롤할 수 있다
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (scroll.width == 0 || scroll.height == 0) return
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                scroll.post { scroll.scrollTo(0, 0) }
            }
        })
        activity.setContentView(scroll)
        fun restoreBarContrast() {
            activity.window.decorView.systemUiVisibility =
                if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val mask =
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                activity.window.insetsController?.setSystemBarsAppearance(
                    if (light) mask else 0,
                    mask
                )
            }
        }
        activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { focused -> if (focused) scroll.post { restoreBarContrast() } }
        scroll.post { if (activity.hasWindowFocus()) restoreBarContrast() }
        body.requestFocus()
        return body
    }

    /** 차량·휴대폰 사건 화면이 함께 쓰는 근거 제목. 본문 글자가 두 번 커지지 않게 한다 */
    fun evidenceHeading(body: LinearLayout, title: String, subtitle: String? = null) {
        val car = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val scale = if (car) (activity.resources.configuration.screenWidthDp / 1100f).coerceIn(
            1f,
            1.65f
        ) else 1f
        body.addView(text(title, 28f * scale, true))
        if (!subtitle.isNullOrBlank()) body.addView(
            text(
                subtitle,
                16f * scale
            ).apply { setTextColor(muted) })
    }

    fun heading(body: LinearLayout, title: String, subtitle: String) {
        body.post { (body.parent as? ScrollView)?.scrollTo(0, 0) }
        body.addView(text(activity.getString(R.string.app_name), 17f, true).apply {
            setTextColor(
                blue
            )
        })
        body.addView(text(title, 30f, true))
        body.addView(text(subtitle, 17f).apply { setTextColor(muted) })
    }

    fun disclosure(
        body: LinearLayout,
        title: String,
        details: LinearLayout,
        collapsedLabel: String? = null,
        expandedLabel: String? = null,
        revealOnExpand: Boolean = false,
        revealTarget: View? = null
    ) {
        details.visibility = View.GONE
        val toggle = textButton(collapsedLabel ?: "$title 펼치기") {}
        fun announceState(expanded: Boolean) {
            if (android.os.Build.VERSION.SDK_INT >= 30) toggle.stateDescription =
                if (expanded) "펼쳐짐" else "접힘"
        }
        announceState(false)
        toggle.setOnClickListener {
            val expanded = details.visibility != View.VISIBLE
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.text =
                if (expanded) expandedLabel ?: "$title 접기" else collapsedLabel ?: "$title 펼치기"
            announceState(expanded)
            if (expanded && (revealOnExpand || revealTarget != null)) details.post {
                val target = revealTarget ?: details
                if (details.visibility == View.VISIBLE && target.height > 0)
                    target.requestRectangleOnScreen(
                        android.graphics.Rect(
                            0,
                            0,
                            target.width,
                            target.height
                        ), false
                    )
            }
        }
        body.addView(toggle)
        body.addView(details)
    }

    fun notice(value: String) = text(value, 19f).apply {
        setPadding(dp(20), dp(22), dp(20), dp(22))
        background = GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = dp(14).toFloat()
        }
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(24)
        }
    }

    /** Pleos 텍스트 버튼: 보조 정보, 배경 채움 없음 */
    fun textButton(value: String, action: () -> Unit): Button = textButton(value, false, action)

    /** 중립 보조 버튼. 비활성, 눌림, 포커스 표시를 유지한다 */
    fun neutralTextButton(value: String, action: () -> Unit): Button =
        textButton(value, true, action)

    private fun textButton(value: String, neutral: Boolean, action: () -> Unit): Button =
        Button(activity).apply {
            val automotive =
                activity.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
            text = value
            isAllCaps = false
            textSize = if (automotive) 30f else 18f
            typeface = PleosTypography.face()
            minHeight = dp(if (automotive) 80 else 56)
            minimumHeight = minHeight
            setPadding(dp(24), dp(12), dp(24), dp(12))
            // 공식 text_property.svg 글자색은 #0064FF. 야간에는 읽기 쉬운 테마 색을 쓴다
            val labelColor = if (neutral) ink else if (light) Color.parseColor("#0064FF") else blue
            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(-android.R.attr.state_enabled),
                        intArrayOf()
                    ), intArrayOf(muted, labelColor)
                )
            )
            elevation = 0f
            stateListAnimator = null
            backgroundTintList = null
            fun shape(color: Int, focused: Boolean = false) = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(24).toFloat()
                if (focused) setStroke(dp(3), ink)
            }

            val states = StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), shape(Color.TRANSPARENT))
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    shape(if (light) 0x1A131417 else 0x1AFFFFFF)
                )
                addState(intArrayOf(android.R.attr.state_focused), shape(Color.TRANSPARENT, true))
                addState(intArrayOf(), shape(Color.TRANSPARENT))
            }
            background = RippleDrawable(
                ColorStateList.valueOf(if (light) 0x1A131417 else 0x1AFFFFFF),
                states,
                null
            )
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            setOnClickListener { action() }
        }

    fun button(value: String, primary: Boolean = false, action: () -> Unit): Button =
        button(value, primary, 24f, action)

    fun button(
        value: String,
        primary: Boolean = false,
        automotiveTextSizeSp: Float,
        action: () -> Unit
    ): Button =
        styledButton(value, primary, automotiveTextSizeSp, false, action)

    /** 머리글 보조 버튼. 같은 상태 색을 쓰고 전체 폭 글자 배율은 적용하지 않는다 */
    fun compactButton(value: String, action: () -> Unit): Button =
        styledButton(value, false, 18f, true, action)

    private fun styledButton(
        value: String,
        primary: Boolean,
        automotiveTextSizeSp: Float,
        compact: Boolean,
        action: () -> Unit
    ): Button {
        val automotive = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        return Button(activity).apply {
            // 분할 화면을 포함해 실제 버튼 폭을 쓴다. 시스템 글꼴 배율은 유지한다
            if (automotive && !compact) addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                val width = right - left
                if (width > 0 && width != oldRight - oldLeft) {
                    val scale =
                        (width / resources.displayMetrics.density / 760f).coerceIn(1f, 1.35f)
                    val size = maxOf(30f, automotiveTextSizeSp) * scale
                    if (kotlin.math.abs(
                            textSize - android.util.TypedValue.applyDimension(
                                android.util.TypedValue.COMPLEX_UNIT_SP,
                                size,
                                resources.displayMetrics
                            )
                        ) > .1f
                    )
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size)
                }
            }
            text = value
            textSize = if (automotive && !compact) maxOf(30f, automotiveTextSizeSp) else 18f
            isAllCaps = false
            typeface = PleosTypography.face()
            minHeight = dp(if (compact) 48 else if (automotive) 80 else 56)
            minimumHeight = minHeight
            val horizontalPadding = dp(if (automotive && !compact) 24 else 16)
            setPadding(horizontalPadding, dp(12), horizontalPadding, dp(12))
            gravity = android.view.Gravity.CENTER
            elevation = 0f
            stateListAnimator = null
            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(-android.R.attr.state_enabled),
                        intArrayOf()
                    ),
                    intArrayOf(
                        muted,
                        if (primary) (if (light) Color.WHITE else Color.parseColor("#131417")) else ink
                    )
                )
            )
            // 공식 Pleos basic_hierarchy.svg: basic #131417/5%, filled #44464E
            // basic_size.svg medium: 높이 80, 모서리 24, Asta Sans 글자
            val fill =
                if (primary) Color.parseColor(if (light) "#44464E" else "#E0E1E6") else if (light) 0x0D131417 else 0x0DFFFFFF

            fun shape(color: Int, focused: Boolean = false) = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(if (automotive) 24 else 17).toFloat()
                if (focused) setStroke(dp(3), ink)
            }

            val states = StateListDrawable().apply {
                addState(
                    intArrayOf(-android.R.attr.state_enabled),
                    shape(if (light) 0x0D131417 else 0x0DFFFFFF)
                )
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    shape(if (primary) Color.parseColor(if (light) "#313236" else "#C7C9CE") else if (light) 0x1A131417 else 0x1AFFFFFF)
                )
                addState(intArrayOf(android.R.attr.state_focused), shape(fill, true))
                addState(intArrayOf(), shape(fill))
            }
            backgroundTintList = null
            background = RippleDrawable(
                ColorStateList.valueOf(if (light) 0x1A131417 else 0x1AFFFFFF),
                states,
                null
            )
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            setOnClickListener { action() }
        }
    }
}
