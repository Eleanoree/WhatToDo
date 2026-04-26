package com.example.whattodo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.NumberPicker
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.util.Pair
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class BrandTabFragment : Fragment(R.layout.fragment_brand_tab) {

    enum class Kind {
        SCHEDULE,
        DATA,
        SETTINGS
    }

    private lateinit var repository: TaskRepository
    private lateinit var rootView: View
    private lateinit var pillView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var scheduleRangeCard: View
    private lateinit var scheduleMonthButton: MaterialButton
    private lateinit var schedulePrevButton: MaterialButton
    private lateinit var scheduleTodayButton: MaterialButton
    private lateinit var scheduleNextButton: MaterialButton
    private lateinit var dataRangeCard: View
    private lateinit var dataPeriodButton: MaterialButton
    private lateinit var dataPeriodHint: TextView
    private lateinit var contentContainer: LinearLayout

    private val kind: Kind by lazy {
        Kind.values()[requireArguments().getInt(ARG_KIND)]
    }

    private val monthLabelFormatter = SimpleDateFormat("yyyy 年 M 月", Locale.getDefault())
    private val dateLabelFormatter = SimpleDateFormat("MM/dd", Locale.getDefault())
    private val timeLabelFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var currentCalendarMonthMillis: Long = startOfMonth(System.currentTimeMillis())
    private var selectedScheduleDateMillis: Long? = null
    private lateinit var currentAnalysisWindow: AnalysisWindow
    private var currentTrendDisplayMode: TrendDisplayMode = TrendDisplayMode.DAILY
    private var currentDataRange: DataRange = DataRange.WEEK
    private var latestScheduleTasks: List<TaskItem> = emptyList()
    private var latestDashboard: DashboardData? = null
    private var latestFocusSessions: List<FocusSessionItem> = emptyList()
    private var latestRangeAnalytics: RangeAnalytics? = null
    private lateinit var firebaseAccountManager: FirebaseAccountManager
    private var firebaseSyncInProgress: Boolean = false
    private var firebaseSyncButton: MaterialButton? = null
    private val firebaseSyncHandler = Handler(Looper.getMainLooper())
    private var firebaseSyncDotCount: Int = 0
    private val firebaseSyncDotsRunnable = object : Runnable {
        override fun run() {
            if (!firebaseSyncInProgress) return
            firebaseSyncDotCount = (firebaseSyncDotCount + 1) % 4
            firebaseSyncButton?.text = firebaseSyncButtonText()
            firebaseSyncHandler.postDelayed(this, 360L)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TaskRepository(requireContext())
        firebaseAccountManager = FirebaseAccountManager(requireContext())
        bindViews(view)
        setupInsets()
        setupBackHandling()
        if (kind == Kind.DATA) {
            restoreAnalysisWindow(savedInstanceState)
        }
        if (kind == Kind.SCHEDULE) {
            restoreScheduleState(savedInstanceState)
            setupScheduleControls()
            dataRangeCard.isVisible = false
        } else {
            scheduleRangeCard.isVisible = false
            dataRangeCard.isVisible = kind == Kind.DATA
            if (kind == Kind.DATA) {
                setupDataControls()
            }
        }
        when (kind) {
            Kind.SCHEDULE -> observeSchedule()
            Kind.DATA -> observeData()
            Kind.SETTINGS -> renderSettings()
        }
    }

    override fun onDestroyView() {
        firebaseSyncHandler.removeCallbacks(firebaseSyncDotsRunnable)
        firebaseSyncButton = null
        super.onDestroyView()
    }

    private fun bindViews(view: View) {
        rootView = view.findViewById(R.id.brandTabRoot)
        pillView = view.findViewById(R.id.brandHeroPillText)
        titleView = view.findViewById(R.id.brandHeroTitleText)
        subtitleView = view.findViewById(R.id.brandHeroSubtitleText)
        scheduleRangeCard = view.findViewById(R.id.brandRangeCard)
        scheduleMonthButton = view.findViewById(R.id.brandScheduleMonthButton)
        schedulePrevButton = view.findViewById(R.id.brandSchedulePrevButton)
        scheduleTodayButton = view.findViewById(R.id.brandScheduleTodayButton)
        scheduleNextButton = view.findViewById(R.id.brandScheduleNextButton)
        dataRangeCard = view.findViewById(R.id.brandDataRangeCard)
        dataPeriodButton = view.findViewById(R.id.brandDataPeriodButton)
        dataPeriodHint = view.findViewById(R.id.brandDataPeriodHint)
        contentContainer = view.findViewById(R.id.brandTabContentContainer)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun observeSchedule() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                repository.dashboardFlow.collect { dashboard ->
                    latestScheduleTasks = dashboard.tasks
                    renderSchedule(dashboard.tasks)
                }
            }
        }
    }

    private fun setupBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (kind != Kind.SCHEDULE) {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                    return
                }
                if (selectedScheduleDateMillis != null) {
                    selectedScheduleDateMillis = null
                    renderSchedule(latestScheduleTasks)
                    return
                }
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    repository.dashboardFlow.collect { dashboard ->
                        latestDashboard = dashboard
                        refreshRangeAnalytics()
                    }
                }
                launch {
                    repository.focusSessionsFlow.collect { sessions ->
                        latestFocusSessions = sessions
                        refreshRangeAnalytics()
                    }
                }
            }
        }
    }

    private fun renderSchedule(tasks: List<TaskItem>) {
        pillView.text = getString(R.string.nav_schedule)
        titleView.text = getString(R.string.schedule_title)

        val monthStart = startOfMonth(currentCalendarMonthMillis)
        scheduleMonthButton.text = monthLabelFormatter.format(Date(monthStart))

        val currentDay = startOfDay(System.currentTimeMillis())
        if (selectedScheduleDateMillis == null) {
            subtitleView.text = getString(R.string.schedule_calendar_hint)
            contentContainer.removeAllViews()
            renderMonthOverview(tasks, monthStart, currentDay)
        } else {
            val selectedDay = selectedScheduleDateMillis ?: monthStart
            subtitleView.text = formatScheduleDateTitle(selectedDay)
            contentContainer.removeAllViews()
            renderDayDetail(tasks, selectedDay, currentDay)
        }
    }

    private fun renderData(analytics: RangeAnalytics) {
        pillView.text = getString(R.string.nav_data)
        titleView.text = getString(R.string.data_title)
        subtitleView.text = analytics.window.label

        contentContainer.removeAllViews()

        contentContainer.addView(completionRingCard(analytics))

        val trendSeries = latestDashboard?.tasks?.let {
            buildTrendSeries(it, analytics.window, currentTrendDisplayMode)
        } ?: analytics.trend
        contentContainer.addView(trendLineCard(trendSeries, analytics.window))
    }

    private fun refreshRangeAnalytics() {
        val dashboard = latestDashboard ?: return
        val sessions = latestFocusSessions
        if (!this::currentAnalysisWindow.isInitialized) return
        val analytics = buildRangeAnalytics(dashboard, sessions, currentAnalysisWindow)
        latestRangeAnalytics = analytics
        currentDataRange = analytics.window.trendBucketMode.toLegacyDataRange()
        updateAnalysisWindowUi()
        renderData(analytics)
    }

    private fun updateAnalysisWindowUi() {
        if (!this::currentAnalysisWindow.isInitialized) return
        dataPeriodButton.text = currentAnalysisWindow.label
        dataPeriodHint.text = currentAnalysisWindow.subtitle
    }

    private fun buildRangeAnalytics(
        dashboard: DashboardData,
        focusSessions: List<FocusSessionItem>,
        window: AnalysisWindow,
    ): RangeAnalytics {
        val tasks = dashboard.tasks
        val tasksInRange = tasks.filter { task ->
            listOfNotNull(
                task.dueAtMillis,
                task.createdAtMillis,
                task.completedAtMillis,
            ).any { it in window.startMillis until window.endMillisExclusive }
        }
        val completedTasks = tasksInRange.filter { it.completedAtMillis != null }
        val activeCount = tasksInRange.count { !it.isCompleted }
        val focusMinutes = focusSessions
            .filter { it.isCompleted && it.completedAtMillis in window.startMillis until window.endMillisExclusive }
            .sumOf { focusSessionMinutes(it) }

        return RangeAnalytics(
            window = window,
            completedCount = completedTasks.size,
            activeCount = activeCount,
            totalCount = tasksInRange.size,
            completionRate = calculatePercentage(completedTasks.size, tasksInRange.size),
            focusMinutes = focusMinutes,
            trend = buildTrendSeries(tasks, window, window.trendBucketMode.toTrendDisplayMode()),
        )
    }

    private fun buildTrendSeries(
        tasks: List<TaskItem>,
        window: AnalysisWindow,
        mode: TrendDisplayMode,
    ): List<DayCompletionStat> {
        val completedTasks = tasks.filter { it.completedAtMillis != null }
        return when (mode) {
            TrendDisplayMode.DAILY -> buildDailyTrendSeries(completedTasks, window)
            TrendDisplayMode.WEEKLY -> buildWeeklyTrendSeries(completedTasks, window.endMillisExclusive, 6)
            TrendDisplayMode.MONTHLY -> buildMonthlyTrendSeries(completedTasks, window.endMillisExclusive, 6)
        }
    }

    private fun buildDailyTrendSeries(completedTasks: List<TaskItem>, window: AnalysisWindow): List<DayCompletionStat> {
        val result = mutableListOf<DayCompletionStat>()
        var cursor = startOfDay(window.startMillis)
        while (cursor < window.endMillisExclusive) {
            val next = minOf(cursor + DAY_MILLIS, window.endMillisExclusive)
            val count = completedTasks.count { task ->
                val completedAt = task.completedAtMillis ?: return@count false
                completedAt in cursor until next
            }
            result += DayCompletionStat(
                label = SimpleDateFormat("M/d", Locale.getDefault()).format(Date(cursor)),
                secondaryLabel = weekdayShortLabel(cursor),
                completedCount = count,
            )
            cursor = next
        }
        return result
    }

    private fun buildWeeklyTrendSeries(
        completedTasks: List<TaskItem>,
        anchorEndMillis: Long,
        bucketCount: Int,
    ): List<DayCompletionStat> {
        val result = mutableListOf<DayCompletionStat>()
        val anchorStart = startOfWeek(anchorEndMillis)
        var cursor = anchorStart - ((bucketCount - 1) * 7L * DAY_MILLIS)
        repeat(bucketCount) { index ->
            val next = cursor + (7L * DAY_MILLIS)
            val count = completedTasks.count { task ->
                val completedAt = task.completedAtMillis ?: return@count false
                completedAt in cursor until next
            }
            result += DayCompletionStat(
                label = SimpleDateFormat("M/d", Locale.getDefault()).format(Date(cursor)),
                secondaryLabel = "第${index + 1}週",
                completedCount = count,
            )
            cursor = next
        }
        return result
    }

    private fun buildMonthlyTrendSeries(
        completedTasks: List<TaskItem>,
        anchorEndMillis: Long,
        bucketCount: Int,
    ): List<DayCompletionStat> {
        val result = mutableListOf<DayCompletionStat>()
        val cursorCalendar = Calendar.getInstance().apply {
            timeInMillis = startOfMonth(anchorEndMillis)
            add(Calendar.MONTH, -(bucketCount - 1))
        }
        repeat(bucketCount) {
            val start = startOfMonth(cursorCalendar.timeInMillis)
            val next = startOfNextMonth(start)
            val count = completedTasks.count { task ->
                val completedAt = task.completedAtMillis ?: return@count false
                completedAt in start until next
            }
            result += DayCompletionStat(
                label = SimpleDateFormat("M/d", Locale.getDefault()).format(Date(start)),
                secondaryLabel = "${cursorCalendar.get(Calendar.MONTH) + 1} 月",
                completedCount = count,
            )
            cursorCalendar.add(Calendar.MONTH, 1)
        }
        return result
    }

    private fun weekdayShortLabel(millis: Long): String {
        return when (Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "一"
            Calendar.TUESDAY -> "二"
            Calendar.WEDNESDAY -> "三"
            Calendar.THURSDAY -> "四"
            Calendar.FRIDAY -> "五"
            Calendar.SATURDAY -> "六"
            else -> "日"
        }
    }

    private fun analysisWindowThisWeek(): AnalysisWindow {
        val now = System.currentTimeMillis()
        return AnalysisWindow(
            label = getString(R.string.data_period_this_week),
            subtitle = getString(R.string.data_week_subtitle),
            startMillis = startOfWeek(now),
            endMillisExclusive = now + 1L,
            trendBucketMode = TrendBucketMode.DAILY,
        )
    }

    private fun analysisWindowLastWeek(): AnalysisWindow {
        val now = System.currentTimeMillis()
        val end = startOfWeek(now)
        return AnalysisWindow(
            label = getString(R.string.data_period_last_week),
            subtitle = "看上一週整週的完成節奏。",
            startMillis = end - 7L * DAY_MILLIS,
            endMillisExclusive = end,
            trendBucketMode = TrendBucketMode.DAILY,
        )
    }

    private fun analysisWindowPrevWeek(): AnalysisWindow {
        val now = System.currentTimeMillis()
        val end = startOfWeek(now) - 7L * DAY_MILLIS
        return AnalysisWindow(
            label = getString(R.string.data_period_prev_week),
            subtitle = "看上上週整週的完成節奏。",
            startMillis = end - 7L * DAY_MILLIS,
            endMillisExclusive = end,
            trendBucketMode = TrendBucketMode.DAILY,
        )
    }

    private fun analysisWindowThisMonth(): AnalysisWindow {
        val now = System.currentTimeMillis()
        return AnalysisWindow(
            label = getString(R.string.data_period_this_month),
            subtitle = getString(R.string.data_month_subtitle),
            startMillis = startOfMonth(now),
            endMillisExclusive = now + 1L,
            trendBucketMode = TrendBucketMode.WEEKLY,
        )
    }

    private fun analysisWindowLastMonth(): AnalysisWindow {
        val now = System.currentTimeMillis()
        val end = startOfMonth(now)
        val start = Calendar.getInstance().apply {
            timeInMillis = end
            add(Calendar.MONTH, -1)
        }.let { startOfMonth(it.timeInMillis) }
        return AnalysisWindow(
            label = getString(R.string.data_period_last_month),
            subtitle = "看上個月每一週的完成分布。",
            startMillis = start,
            endMillisExclusive = end,
            trendBucketMode = TrendBucketMode.WEEKLY,
        )
    }

    private fun analysisWindowHalfYear(): AnalysisWindow {
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply {
            timeInMillis = startOfMonth(now)
            add(Calendar.MONTH, -5)
        }.let { startOfMonth(it.timeInMillis) }
        return AnalysisWindow(
            label = getString(R.string.data_period_half_year),
            subtitle = "看近半年每個月的完成分布。",
            startMillis = start,
            endMillisExclusive = now + 1L,
            trendBucketMode = TrendBucketMode.MONTHLY,
        )
    }

    private fun buildCustomAnalysisWindow(startMillis: Long, endMillis: Long): AnalysisWindow {
        val normalizedStart = startOfDay(minOf(startMillis, endMillis))
        val normalizedEnd = startOfDay(maxOf(startMillis, endMillis)) + DAY_MILLIS
        val spanDays = ((normalizedEnd - normalizedStart) / DAY_MILLIS).toInt().coerceAtLeast(1)
        val trendMode = when {
            spanDays <= 21 -> TrendBucketMode.DAILY
            spanDays <= 120 -> TrendBucketMode.WEEKLY
            else -> TrendBucketMode.MONTHLY
        }
        val label = getString(R.string.data_range_picker_custom)
        val subtitle = "${SimpleDateFormat("M/d", Locale.getDefault()).format(Date(normalizedStart))} - ${SimpleDateFormat("M/d", Locale.getDefault()).format(Date(normalizedEnd - 1L))}"
        return AnalysisWindow(
            label = label,
            subtitle = subtitle,
            startMillis = normalizedStart,
            endMillisExclusive = normalizedEnd,
            trendBucketMode = trendMode,
        )
    }

    private fun focusSessionMinutes(session: FocusSessionItem): Int {
        val elapsed = ((session.completedAtMillis - session.startedAtMillis).coerceAtLeast(0L) / 60_000L).toInt()
        return elapsed.takeIf { it > 0 } ?: session.plannedMinutes
    }

    private fun calculatePercentage(completed: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((completed * 100f) / total).toInt().coerceIn(0, 100)
    }

    private fun TrendBucketMode.toLegacyDataRange(): DataRange {
        return when (this) {
            TrendBucketMode.DAILY -> DataRange.WEEK
            TrendBucketMode.WEEKLY,
            TrendBucketMode.MONTHLY -> DataRange.MONTH
        }
    }

    private fun TrendBucketMode.toTrendDisplayMode(): TrendDisplayMode {
        return when (this) {
            TrendBucketMode.DAILY -> TrendDisplayMode.DAILY
            TrendBucketMode.WEEKLY -> TrendDisplayMode.WEEKLY
            TrendBucketMode.MONTHLY -> TrendDisplayMode.MONTHLY
        }
    }

    private fun renderSettings() {
        pillView.text = getString(R.string.nav_settings)
        titleView.text = getString(R.string.settings_title)
        subtitleView.text = getString(R.string.settings_subtitle)

        contentContainer.removeAllViews()
        contentContainer.addView(firebaseStatusCard())
        contentContainer.addView(firebaseAccountCard())
        contentContainer.addView(settingCard(getString(R.string.settings_appearance), "奶杏粉色主題、卡片與字級"))
        contentContainer.addView(settingCard(getString(R.string.settings_notifications), "提醒與鬧鐘偏好"))
        contentContainer.addView(settingCard(getString(R.string.settings_sound), "完成音效與提示音"))
        contentContainer.addView(settingCard(getString(R.string.settings_data), "同步、備份與匯出"))
        contentContainer.addView(settingCard(getString(R.string.settings_about), "WhatToDo v1.0"))
    }

    private fun firebaseStatusCard(): View {
        val state = repository.cloudConnectionState()
        val isReady = state.isConfigured && state.isSignedIn
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(20f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(
                requireContext(),
                if (isReady) R.color.brand_powder else R.color.priority_medium_bg
            )
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
            cardElevation = dp(0f)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(14), dpInt(14), dpInt(14), dpInt(14))
        }

        content.addView(TextView(requireContext()).apply {
            text = getString(R.string.settings_firebase_status_title)
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        })
        content.addView(TextView(requireContext()).apply {
            text = if (isReady) {
                getString(R.string.settings_firebase_status_ready)
            } else {
                getString(R.string.settings_firebase_status_not_ready)
            }
            textSize = 12.5f
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isReady) R.color.text_primary else R.color.priority_high_text
                )
            )
            setPadding(0, dpInt(6), 0, 0)
        })

        content.addView(MaterialButton(requireContext()).apply {
            firebaseSyncButton = this
            text = firebaseSyncButtonText()
            isAllCaps = false
            isEnabled = isReady && !firebaseSyncInProgress
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), if (isReady) R.color.brand_powder else R.color.surface_variant)
            )
            strokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.card_stroke))
            strokeWidth = dpInt(1)
            cornerRadius = dpInt(16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
            setOnClickListener { syncFirebaseNow() }
        })

        card.addView(content)
        return card
    }

    private fun firebaseAccountCard(): View {
        val state = firebaseAccountManager.bindingState()
        val configuredColor = if (state.isConfigured) R.color.brand_powder else R.color.priority_medium_bg
        val statusText = if (state.isConfigured) {
            getString(R.string.settings_google_account_status_ready)
        } else {
            getString(R.string.settings_google_account_status_missing)
        }

        val card = MaterialCardView(requireContext()).apply {
            radius = dp(24f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), configuredColor)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.hero_background))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
            cardElevation = dp(0f)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(16))
        }

        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(requireContext()).apply {
            text = getString(R.string.settings_google_account)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        })

        content.addView(header)
        content.addView(TextView(requireContext()).apply {
            text = statusText
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setPadding(0, dpInt(10), 0, 0)
        })

        content.addView(TextView(requireContext()).apply {
            text = if (state.isSignedIn) {
                getString(R.string.settings_google_account_signed_in_brief)
            } else {
                getString(R.string.settings_google_account_signed_out)
            }
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setPadding(0, dpInt(12), 0, 0)
        })

        val buttonRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dpInt(14), 0, 0)
        }

        buttonRow.addView(MaterialButton(requireContext()).apply {
            text = if (state.isConfigured) {
                if (state.isSignedIn) {
                    getString(R.string.settings_google_account_rebind)
                } else {
                    getString(R.string.settings_google_account_bind)
                }
            } else {
                getString(R.string.settings_google_account_setup)
            }
            isAllCaps = false
            strokeWidth = 0
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.brand_powder)
            )
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setOnClickListener { bindGoogleAccount() }
        })

        if (state.isSignedIn) {
            buttonRow.addView(MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(R.string.settings_google_account_unbind)
                isAllCaps = false
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dpInt(10)
                }
                setOnClickListener { unbindGoogleAccount() }
            })
        }

        content.addView(buttonRow)
        card.addView(content)
        return card
    }

    private fun bindGoogleAccount() {
        if (!firebaseAccountManager.ensureInitialized()) {
            showFirebaseSetupDialog()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                firebaseAccountManager.signInWithGoogle(requireActivity())
            }.onSuccess { user ->
                val successLabel = user.email?.takeIf { it.isNotBlank() }
                    ?: user.displayName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.settings_google_account_user_unknown)
                Snackbar.make(
                    rootView,
                getString(R.string.settings_google_account_binding_success, successLabel),
                Snackbar.LENGTH_LONG
            ).show()
            syncFirebaseNow(showSuccessMessage = false, showSetupDialogOnMissingAuth = false)
            if (kind == Kind.SETTINGS) {
                    renderSettings()
                }
            }.onFailure { throwable ->
                val message = throwable.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.settings_google_account_binding_failed)
                Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun firebaseSyncButtonText(): String {
        return if (firebaseSyncInProgress) {
            buildString {
                append(getString(R.string.settings_firebase_syncing))
                append(' ')
                repeat(firebaseSyncDotCount.coerceIn(1, 3)) { append("🐾") }
            }
        } else {
            getString(R.string.settings_firebase_sync_now)
        }
    }

    private fun syncFirebaseNow(
        showSuccessMessage: Boolean = true,
        showSetupDialogOnMissingAuth: Boolean = true,
    ) {
        val state = repository.cloudConnectionState()
        if (!state.isConfigured || !state.isSignedIn) {
            if (showSetupDialogOnMissingAuth) {
                showFirebaseSetupDialog()
            }
            return
        }

        if (firebaseSyncInProgress) return

        viewLifecycleOwner.lifecycleScope.launch {
            firebaseSyncInProgress = true
            firebaseSyncDotCount = 1
            if (kind == Kind.SETTINGS) {
                renderSettings()
            }
            firebaseSyncHandler.removeCallbacks(firebaseSyncDotsRunnable)
            firebaseSyncHandler.post(firebaseSyncDotsRunnable)
            try {
                if (showSuccessMessage) {
                    Snackbar.make(
                        rootView,
                        getString(R.string.settings_firebase_syncing),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                repository.syncWithCloud()
                if (showSuccessMessage) {
                    Snackbar.make(
                        rootView,
                        getString(R.string.settings_firebase_sync_success),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            } catch (throwable: Throwable) {
                showFirebaseSyncFailureDialog(throwable)
            } finally {
                firebaseSyncInProgress = false
                firebaseSyncHandler.removeCallbacks(firebaseSyncDotsRunnable)
                if (kind == Kind.SETTINGS) {
                    renderSettings()
                }
            }
        }
    }

    private fun showFirebaseSyncFailureDialog(throwable: Throwable) {
        val message = describeFirebaseSyncFailure(throwable)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_firebase_sync_error_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.settings_firebase_sync_retry)) { _, _ ->
                syncFirebaseNow()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun describeFirebaseSyncFailure(throwable: Throwable): String {
        val rawMessage = throwable.message.orEmpty()
        val normalized = rawMessage.lowercase(Locale.getDefault())
        return when {
            normalized.contains("permission_denied") || normalized.contains("permission denied") || normalized.contains("missing or insufficient permissions") ->
                "Firestore 拒絕寫入，通常是 Security Rules 尚未部署或目前帳號沒有權限。請先確認 firestore.rules 已發佈。"
            normalized.contains("unauthenticated") || normalized.contains("not signed in") || normalized.contains("sign-in") ->
                "目前 Firebase 帳號尚未登入，請先到設置頁完成 Google 帳號綁定。"
            normalized.contains("network") || normalized.contains("timeout") || normalized.contains("unavailable") || normalized.contains("io exception") ->
                "網路連線不穩或 Firestore 暫時不可用，請稍後再試。"
            normalized.contains("google-services.json") || normalized.contains("firebase") || normalized.contains("web client") ->
                "Firebase 設定還沒完成，請確認正式的 google-services.json 與 SHA-1 已正確設定。"
            rawMessage.isNotBlank() -> rawMessage
            else -> getString(R.string.settings_firebase_sync_failed)
        }
    }

    private fun unbindGoogleAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                firebaseAccountManager.signOut()
            }.onSuccess {
                Snackbar.make(
                    rootView,
                    R.string.settings_google_account_signout_success,
                    Snackbar.LENGTH_LONG
                ).show()
                if (kind == Kind.SETTINGS) {
                    renderSettings()
                }
            }.onFailure { throwable ->
                val message = throwable.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.settings_google_account_signout_failed)
                Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showFirebaseSetupDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_google_account_setup_title))
            .setMessage(buildString {
                appendLine(getString(R.string.settings_google_account_setup_message))
                appendLine()
                appendLine("1. 到 Firebase Console 建立專案並加入 Android 應用程式。")
                appendLine("2. 到 Authentication 啟用 Google 登入。")
                appendLine("3. 把 Firebase 的 Application ID、API Key、Project ID 與 Google Web Client ID 填進 strings.xml。")
                appendLine("4. 在 Firebase Console 加入這個 app 的 SHA-1 指紋。")
            })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dataOverviewCard(analytics: AnalyticsSnapshot): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(24f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.hero_background))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(16) }
            cardElevation = dp(0f)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(16))
        }

        content.addView(pill(getString(R.string.data_overview_title), R.color.brand_powder))
        content.addView(TextView(requireContext()).apply {
            text = getString(R.string.data_overview_hint)
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(8), 0, 0)
        })

        val compareRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
        }

        compareRow.addView(overviewMiniCard(
            title = getString(R.string.data_range_week),
            percent = analytics.weeklyCompletionRate,
            detail = "${analytics.weeklyCompleted}/${analytics.weeklyActivityCount} 項",
            accentColorRes = if (currentDataRange == DataRange.WEEK) R.color.brand_powder else R.color.surface_variant,
            highlight = currentDataRange == DataRange.WEEK,
        ))
        compareRow.addView(overviewMiniCard(
            title = getString(R.string.data_range_month),
            percent = analytics.monthlyCompletionRate,
            detail = "${analytics.monthlyCompleted}/${analytics.monthlyActivityCount} 項",
            accentColorRes = if (currentDataRange == DataRange.MONTH) R.color.brand_sky else R.color.surface_variant,
            highlight = currentDataRange == DataRange.MONTH,
            startMargin = 10,
        ))

        content.addView(compareRow)
        content.addView(summaryRow(
            pairOf("本週專注", analytics.focusMinutesThisWeek, R.color.priority_medium_bg, formatMinutes(analytics.focusMinutesThisWeek)),
            pairOf("本週完成", analytics.weeklyCompleted, R.color.priority_low_bg),
            pairOf("本月完成", analytics.monthlyCompleted, R.color.priority_high_bg),
        ))

        card.addView(content)
        return card
    }

    private fun overviewMiniCard(
        title: String,
        percent: Int,
        detail: String,
        accentColorRes: Int,
        highlight: Boolean,
        startMargin: Int = 0,
    ): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(20f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), if (highlight) R.color.brand_powder else R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), if (highlight) R.color.surface else R.color.surface_variant))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (startMargin > 0) leftMargin = dpInt(startMargin)
            }
            cardElevation = dp(0f)
        }

        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(14), dpInt(14), dpInt(14), dpInt(14))
        }

        column.addView(pill(title, accentColorRes, compact = true))

        column.addView(TextView(requireContext()).apply {
            text = "$percent%"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setPadding(0, dpInt(8), 0, 0)
        })

        column.addView(TextView(requireContext()).apply {
            text = detail
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(4), 0, 0)
        })

        card.addView(column)
        return card
    }

    private fun setupScheduleControls() {
        schedulePrevButton.setOnClickListener {
            currentCalendarMonthMillis = shiftMonth(currentCalendarMonthMillis, -1)
            selectedScheduleDateMillis = null
            renderSchedule(latestScheduleTasks)
        }

        scheduleMonthButton.setOnClickListener {
            showMonthPickerDialog()
        }

        scheduleTodayButton.setOnClickListener {
            currentCalendarMonthMillis = startOfMonth(System.currentTimeMillis())
            selectedScheduleDateMillis = null
            renderSchedule(latestScheduleTasks)
        }

        scheduleNextButton.setOnClickListener {
            currentCalendarMonthMillis = shiftMonth(currentCalendarMonthMillis, 1)
            selectedScheduleDateMillis = null
            renderSchedule(latestScheduleTasks)
        }
    }

    private fun setupDataControls() {
        dataPeriodButton.setOnClickListener { showAnalysisWindowDialog() }
        updateAnalysisWindowUi()
    }

    private fun showAnalysisWindowDialog() {
        val options = listOf(
            analysisWindowThisWeek(),
            analysisWindowLastWeek(),
            analysisWindowPrevWeek(),
            analysisWindowThisMonth(),
            analysisWindowLastMonth(),
            analysisWindowHalfYear(),
        )

        var pendingSelection = currentAnalysisWindow

        val dialogView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(12))
        }

        dialogView.addView(MaterialCardView(requireContext()).apply {
            radius = dp(22f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.hero_background))
            cardElevation = dp(0f)
                addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(16))
                addView(pill(getString(R.string.data_range_label), R.color.brand_powder))
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.data_range_picker_title)
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    setPadding(0, dpInt(8), 0, 0)
                })
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.data_range_picker_hint)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    setPadding(0, dpInt(6), 0, 0)
                })
            })
        })

        dialogView.addView(TextView(requireContext()).apply {
            text = "常用區間"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(dpInt(4), dpInt(14), dpInt(4), dpInt(8))
        })

        val optionButtons = options.map { option ->
            createRangeOptionButton(option, option == pendingSelection) { }
        }

        val optionRows = optionButtons.chunked(2)
        fun refreshOptionStyles() {
            optionButtons.forEach { button ->
                val selected = button.isChecked
                button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), if (selected) R.color.hero_background else R.color.surface)
                )
                button.strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), if (selected) R.color.brand_powder else R.color.card_stroke)
                )
            }
        }
        refreshOptionStyles()
        optionButtons.firstOrNull { it.tag == pendingSelection }?.isChecked = true
        optionRows.forEachIndexed { rowIndex, rowButtons ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIndex > 0) topMargin = dpInt(8)
                }
            }
            rowButtons.forEachIndexed { columnIndex, button ->
                (button.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.width = 0
                    it.weight = 1f
                    it.topMargin = 0
                    if (columnIndex > 0) it.leftMargin = dpInt(8)
                }
                button.setOnClickListener {
                    pendingSelection = button.tag as? AnalysisWindow ?: return@setOnClickListener
                    optionButtons.forEach { other -> other.isChecked = other == button }
                    refreshOptionStyles()
                }
                row.addView(button)
            }
            dialogView.addView(row)
        }

        dialogView.addView(createRangeCustomButton {
            showCustomAnalysisRangePicker()
        })

        val confirmButton = MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "確定"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.brand_powder)
            )
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.card_stroke))
            strokeWidth = dpInt(1)
            cornerRadius = dpInt(18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(14) }
        }
        dialogView.addView(confirmButton)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        confirmButton.setOnClickListener {
            currentAnalysisWindow = pendingSelection
            currentTrendDisplayMode = pendingSelection.trendBucketMode.toTrendDisplayMode()
            refreshRangeAnalytics()
            dialog.dismiss()
            showRangeAppliedNotice(pendingSelection)
        }

        dialog.show()
    }

    private fun showCustomAnalysisRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.data_range_picker_custom))
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            val start = selection.first ?: return@addOnPositiveButtonClickListener
            val end = selection.second ?: return@addOnPositiveButtonClickListener
            currentAnalysisWindow = buildCustomAnalysisWindow(start, end)
            currentTrendDisplayMode = currentAnalysisWindow.trendBucketMode.toTrendDisplayMode()
            refreshRangeAnalytics()
        }

        picker.show(parentFragmentManager, "data_range_picker")
    }

    private fun createRangeOptionButton(
        option: AnalysisWindow,
        isSelected: Boolean = false,
        onClick: () -> Unit,
    ): MaterialButton {
        return MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = View.generateViewId()
            tag = option
            text = option.label
            isAllCaps = false
            gravity = Gravity.CENTER_VERTICAL
            isCheckable = true
            isChecked = isSelected
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            strokeColor = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), if (isSelected) R.color.brand_powder else R.color.card_stroke)
            )
            strokeWidth = dpInt(1)
            cornerRadius = dpInt(18)
            setPadding(dpInt(14), dpInt(12), dpInt(14), dpInt(12))
            setOnClickListener { onClick() }
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_data_range_option_paw)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), if (isSelected) R.color.hero_background else R.color.surface)
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpInt(8)
                weight = 1f
            }
        }
    }

    private fun createRangeCustomButton(onClick: () -> Unit): MaterialButton {
        return MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.data_range_picker_custom)
            isAllCaps = false
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.hero_background)
            )
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.card_stroke))
            strokeWidth = dpInt(1)
            cornerRadius = dpInt(18)
            setPadding(dpInt(14), dpInt(12), dpInt(14), dpInt(12))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(10) }
        }
    }

    private fun showRangeAppliedNotice(
        selection: AnalysisWindow,
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("已套用")
            .setMessage("${selection.label}\n${selection.subtitle}")
            .setPositiveButton("好", null)
            .show()
    }

    private fun showMonthPickerDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_schedule_picker, null)
        val yearPicker = dialogView.findViewById<NumberPicker>(R.id.scheduleYearPicker)
        val monthPicker = dialogView.findViewById<NumberPicker>(R.id.scheduleMonthPicker)
        val prevButton = dialogView.findViewById<MaterialButton>(R.id.schedulePickerPrevButton)
        val nextButton = dialogView.findViewById<MaterialButton>(R.id.schedulePickerNextButton)
        val todayButton = dialogView.findViewById<MaterialButton>(R.id.schedulePickerTodayButton)
        val confirmButton = dialogView.findViewById<MaterialButton>(R.id.schedulePickerConfirmButton)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.schedulePickerCancelButton)

        val calendar = Calendar.getInstance().apply { timeInMillis = currentCalendarMonthMillis }
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH) + 1

        val years = (2020..2035).toList()
        yearPicker.minValue = 0
        yearPicker.maxValue = years.lastIndex
        yearPicker.displayedValues = years.map { it.toString() }.toTypedArray()
        yearPicker.value = years.indexOf(currentYear).takeIf { it >= 0 } ?: years.indexOf(2026).coerceAtLeast(0)

        val months = (1..12).map { getString(R.string.schedule_month_format, it) }
        monthPicker.minValue = 0
        monthPicker.maxValue = months.lastIndex
        monthPicker.displayedValues = months.toTypedArray()
        monthPicker.value = currentMonth - 1

        var previewMonthMillis = currentCalendarMonthMillis

        fun syncMonthFromPickers() {
            val selectedYear = years[yearPicker.value]
            val selectedMonth = monthPicker.value + 1
            currentCalendarMonthMillis = startOfMonth(
                Calendar.getInstance().apply {
                    set(Calendar.YEAR, selectedYear)
                    set(Calendar.MONTH, selectedMonth - 1)
                    set(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
            )
            selectedScheduleDateMillis = null
            renderSchedule(latestScheduleTasks)
        }

        prevButton.setOnClickListener {
            previewMonthMillis = shiftMonth(previewMonthMillis, -1)
            val nextCalendar = Calendar.getInstance().apply { timeInMillis = previewMonthMillis }
            yearPicker.value = years.indexOf(nextCalendar.get(Calendar.YEAR)).coerceAtLeast(0)
            monthPicker.value = nextCalendar.get(Calendar.MONTH)
        }

        nextButton.setOnClickListener {
            previewMonthMillis = shiftMonth(previewMonthMillis, 1)
            val nextCalendar = Calendar.getInstance().apply { timeInMillis = previewMonthMillis }
            yearPicker.value = years.indexOf(nextCalendar.get(Calendar.YEAR)).coerceAtLeast(0)
            monthPicker.value = nextCalendar.get(Calendar.MONTH)
        }

        todayButton.setOnClickListener {
            previewMonthMillis = startOfMonth(System.currentTimeMillis())
            val todayCalendar = Calendar.getInstance().apply { timeInMillis = previewMonthMillis }
            yearPicker.value = years.indexOf(todayCalendar.get(Calendar.YEAR)).coerceAtLeast(0)
            monthPicker.value = todayCalendar.get(Calendar.MONTH)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        confirmButton.setOnClickListener {
            syncMonthFromPickers()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun restoreScheduleState(savedInstanceState: Bundle?) {
        currentCalendarMonthMillis = startOfMonth(
            savedInstanceState?.getLong(STATE_CALENDAR_MONTH_MILLIS, System.currentTimeMillis())
                ?: System.currentTimeMillis()
        )
        selectedScheduleDateMillis = savedInstanceState?.getLong(STATE_SELECTED_DAY_MILLIS, -1L)?.takeIf { it >= 0L }
    }

    private fun restoreAnalysisWindow(savedInstanceState: Bundle?) {
        val savedStart = savedInstanceState?.getLong(STATE_ANALYSIS_START, -1L) ?: -1L
        if (savedStart < 0L) {
            currentAnalysisWindow = analysisWindowThisWeek()
            currentDataRange = DataRange.WEEK
            return
        }

        currentAnalysisWindow = AnalysisWindow(
            label = savedInstanceState?.getString(STATE_ANALYSIS_LABEL, getString(R.string.data_period_this_week)).orEmpty(),
            subtitle = savedInstanceState?.getString(STATE_ANALYSIS_SUBTITLE, getString(R.string.data_range_picker_hint)).orEmpty(),
            startMillis = savedStart,
            endMillisExclusive = savedInstanceState?.getLong(STATE_ANALYSIS_END, System.currentTimeMillis() + 1L)
                ?: (System.currentTimeMillis() + 1L),
            trendBucketMode = TrendBucketMode.values()[savedInstanceState?.getInt(STATE_ANALYSIS_MODE, TrendBucketMode.DAILY.ordinal)
                ?: TrendBucketMode.DAILY.ordinal]
        )
        currentTrendDisplayMode = TrendDisplayMode.values()[savedInstanceState?.getInt(
            STATE_TREND_DISPLAY_MODE,
            currentAnalysisWindow.trendBucketMode.ordinal
        ) ?: currentAnalysisWindow.trendBucketMode.ordinal]
        currentDataRange = currentAnalysisWindow.trendBucketMode.toLegacyDataRange()
    }

    private fun renderMonthOverview(tasks: List<TaskItem>, monthStart: Long, todayStart: Long) {
        val monthEnd = startOfNextMonth(monthStart)
        val monthTasks = tasks.filter { task ->
            val due = task.dueAtMillis ?: return@filter false
            due in monthStart until monthEnd
        }
        val monthLabel = monthLabelFormatter.format(Date(monthStart))

        val activeTasks = monthTasks.filterNot { it.isCompleted }
        val overdue = activeTasks.count { task ->
            val due = task.dueAtMillis ?: return@count false
            due < todayStart
        }
        val dueToday = activeTasks.count { task ->
            val due = task.dueAtMillis ?: return@count false
            due in todayStart until todayStart + DAY_MILLIS
        }
        val holidayDays = HolidayCalendarStore.countOfficialHolidayDays(requireContext(), monthStart)

        contentContainer.addView(summaryRow(
            pairOf("本月任務", monthTasks.size, R.color.priority_low_bg),
            pairOf("今天到期", dueToday, R.color.priority_medium_bg),
            pairOf("逾期", overdue, R.color.priority_high_bg),
        ))

        contentContainer.addView(sectionTitle("月曆 · $monthLabel"))
        contentContainer.addView(createCalendarLegend())
        contentContainer.addView(createWeekHeaderRow())
        contentContainer.addView(createMonthGrid(
            tasks = monthTasks,
            monthStart = monthStart,
            todayStart = todayStart,
            onDaySelected = { selectedScheduleDateMillis = it; renderSchedule(latestScheduleTasks) }
        ))

        if (holidayDays > 0) {
            contentContainer.addView(hintCard("本月有 $holidayDays 個國定假日標記，小貓幫你先記著。"))
        }
    }

    private fun renderDayDetail(tasks: List<TaskItem>, selectedDayMillis: Long, todayStart: Long) {
        val dayStart = startOfDay(selectedDayMillis)
        val dayEnd = dayStart + DAY_MILLIS
        val dayTasks = tasks.filter { task ->
            val due = task.dueAtMillis ?: return@filter false
            due in dayStart until dayEnd
        }.sortedWith(compareBy<TaskItem> { it.isCompleted }.thenBy { it.dueAtMillis ?: Long.MAX_VALUE })
        val holiday = holidayInfoForDay(dayStart)

        contentContainer.addView(detailHeaderCard(dayStart, holiday, dayTasks))
        contentContainer.addView(createDetailActionRow(dayTasks))

        if (dayTasks.isEmpty()) {
            contentContainer.addView(hintCard("這一天還沒有代辦，小貓先陪你休息一下。"))
            return
        }

        contentContainer.addView(summaryRow(
            pairOf("當天任務", dayTasks.size, R.color.priority_low_bg),
            pairOf("已完成", dayTasks.count { it.isCompleted }, R.color.priority_medium_bg),
            pairOf("進行中", dayTasks.count { !it.isCompleted }, R.color.priority_high_bg),
        ))
        contentContainer.addView(timelineScaleCard(dayStart, dayTasks))
        dayTasks.forEachIndexed { index, task ->
            contentContainer.addView(timelineTaskRow(task, index, dayTasks.size, dayStart))
        }
    }

    private fun renderScheduleSection(
        title: String,
        tasks: List<TaskItem>,
        emptyHint: String,
    ) {
        contentContainer.addView(sectionTitle(title))
        if (tasks.isEmpty()) {
            contentContainer.addView(hintCard(emptyHint))
            return
        }

        tasks.take(6).forEach { task ->
            contentContainer.addView(taskCard(task))
        }
    }

    private fun createDetailActionRow(dayTasks: List<TaskItem>): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
        }

        row.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "回到月曆"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface))
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.card_stroke))
            strokeWidth = dpInt(1)
            cornerRadius = dpInt(16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                selectedScheduleDateMillis = null
                renderSchedule(latestScheduleTasks)
            }
        })

        row.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "前往主頁編輯"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.brand_powder))
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.card_stroke))
            strokeWidth = dpInt(1)
            cornerRadius = dpInt(16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpInt(10)
            }
            setOnClickListener {
                val targetTask = dayTasks.firstOrNull { !it.isCompleted } ?: dayTasks.firstOrNull()
                if (targetTask != null) {
                    (activity as? ShellActivity)?.openTaskEditor(targetTask.id, R.id.nav_schedule)
                } else {
                    (activity as? ShellActivity)?.selectTab(R.id.nav_home)
                }
            }
        })

        return row
    }

    private fun timelineScaleCard(dayStart: Long, dayTasks: List<TaskItem>): View {
        val timedTasks = dayTasks.filter {
            hasExplicitTime(it.dueAtMillis)
        }
        val hourBounds = timedTasks.mapNotNull { task ->
            task.dueAtMillis?.let {
                Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.HOUR_OF_DAY)
            }
        }
        val startHour = (hourBounds.minOrNull()?.minus(1) ?: 6).coerceAtLeast(6)
        val endHour = (hourBounds.maxOrNull()?.plus(1) ?: 22).coerceAtMost(22)

        val card = MaterialCardView(requireContext()).apply {
            radius = dp(22f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
            cardElevation = dp(0f)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(16), dpInt(14), dpInt(16), dpInt(16))
        }

        val topRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        topRow.addView(pill(getString(R.string.schedule_timeline_label), R.color.brand_powder))
        topRow.addView(TextView(requireContext()).apply {
            text = getString(R.string.schedule_timeline_hint)
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(dpInt(10), 0, 0, 0)
        })
        content.addView(topRow)

        val rulerScroll = android.widget.HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
        }

        val ruler = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        for (hour in startHour..endHour) {
            val major = hour == startHour || hour == endHour || hour % 2 == 0
            val marker = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dpInt(42), LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            marker.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dpInt(2), if (major) dpInt(22) else dpInt(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999f)
                    setColor(ContextCompat.getColor(requireContext(), if (major) R.color.brand_powder else R.color.card_stroke))
                }
            })

            if (major) {
                marker.addView(TextView(requireContext()).apply {
                    text = "%02d:00".format(hour)
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    gravity = Gravity.CENTER
                    setPadding(0, dpInt(6), 0, 0)
                })
            } else {
                marker.addView(TextView(requireContext()).apply {
                    text = "·"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    gravity = Gravity.CENTER
                    setPadding(0, dpInt(6), 0, 0)
                })
            }

            ruler.addView(marker)
        }

        rulerScroll.addView(ruler)
        content.addView(rulerScroll)
        card.addView(content)
        return card
    }

    private fun detailHeaderCard(dayStart: Long, holiday: HolidayInfo?, dayTasks: List<TaskItem>): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(24f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
            cardElevation = dp(0f)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(16))
        }

        content.addView(pill(formatFullDate(dayStart), R.color.brand_powder))

        val holidayView = TextView(requireContext()).apply {
            text = holiday?.label ?: "普通日"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setPadding(0, dpInt(10), 0, 0)
        }

        val hintView = TextView(requireContext()).apply {
            text = when (holiday?.tone) {
                HolidayTone.OFFICIAL, HolidayTone.SUBSTITUTE -> "這一天被標記為小小假期，安排可以更輕一點。"
                HolidayTone.WEEKEND -> "這一天是週末，小貓先幫你留一點喘息空間。"
                else -> "點點看這一天的任務，小貓會替你整理。"
            }
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(6), 0, 0)
        }

        content.addView(holidayView)
        content.addView(hintView)

        val totalCount = dayTasks.size
        val completedCount = dayTasks.count { it.isCompleted }
        val activeCount = totalCount - completedCount
        val statRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(14) }
        }
        statRow.addView(dayStatCard("總計", totalCount.toString(), R.color.priority_low_bg))
        statRow.addView(dayStatCard("完成", completedCount.toString(), R.color.brand_sky, startMargin = 8))
        statRow.addView(dayStatCard("進行中", activeCount.toString(), R.color.priority_medium_bg, startMargin = 8))
        content.addView(statRow)

        val scheduleHint = TextView(requireContext()).apply {
            text = if (holiday?.tone == HolidayTone.OFFICIAL || holiday?.tone == HolidayTone.SUBSTITUTE) {
                "今天有假日標記，節奏可以排得更柔一點。"
            } else {
                "這一天的任務會在下方逐筆列出。"
            }
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(12), 0, 0)
        }
        content.addView(scheduleHint)

        card.addView(content)
        return card
    }

    private fun createWeekHeaderRow(): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
        }

        listOf(
            getString(R.string.day_monday),
            getString(R.string.day_tuesday),
            getString(R.string.day_wednesday),
            getString(R.string.day_thursday),
            getString(R.string.day_friday),
            getString(R.string.day_saturday),
            getString(R.string.day_sunday),
        ).forEachIndexed { index, day ->
            row.addView(TextView(requireContext()).apply {
                text = day
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (index > 0) setPadding(0, 0, 0, 0)
            })
        }
        return row
    }

    private fun createMonthGrid(
        tasks: List<TaskItem>,
        monthStart: Long,
        todayStart: Long,
        onDaySelected: (Long) -> Unit
    ): View {
        val grid = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(10) }
        }

        val calendar = Calendar.getInstance().apply { timeInMillis = monthStart }
        val firstDayOffset = mondayFirstIndex(calendar.get(Calendar.DAY_OF_WEEK))
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayTaskMap = tasks.groupBy { startOfDay(it.dueAtMillis ?: 0L) }
        val dayCompletionMap = tasks
            .mapNotNull { task -> task.completedAtMillis?.let { startOfDay(it) } }
            .groupBy { it }
        var currentDay = 1
        var cellIndex = 0

        while (currentDay <= daysInMonth) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            repeat(7) { columnIndex ->
                val shouldShowDay = cellIndex >= firstDayOffset && currentDay <= daysInMonth
                if (shouldShowDay) {
                    val dayMillis = dayStartForMonth(monthStart, currentDay)
                    val dayTasks = dayTaskMap[dayMillis].orEmpty()
                    row.addView(createMonthDayCell(
                        dayMillis = dayMillis,
                        dayTasks = dayTasks,
                        completionCount = dayCompletionMap[dayMillis].orEmpty().size,
                        isToday = dayMillis == todayStart,
                        holiday = holidayInfoForDay(dayMillis),
                        isSelected = selectedScheduleDateMillis == dayMillis,
                        onClick = onDaySelected
                    ))
                    currentDay += 1
                } else {
                    row.addView(spacerCell(requireContext()))
                }
                cellIndex += 1
            }

            grid.addView(row)
        }
        return grid
    }

    private fun createMonthDayCell(
        dayMillis: Long,
        dayTasks: List<TaskItem>,
        completionCount: Int,
        isToday: Boolean,
        holiday: HolidayInfo?,
        isSelected: Boolean,
        onClick: (Long) -> Unit
    ): View {
        val isWeekend = isWeekend(dayMillis)
        val backgroundRes = when {
            isSelected -> R.color.hero_background
            holiday?.tone == HolidayTone.OFFICIAL -> R.color.priority_high_bg
            holiday?.tone == HolidayTone.SUBSTITUTE -> R.color.priority_medium_bg
            isToday -> R.color.priority_low_bg
            isWeekend -> R.color.surface_variant
            else -> R.color.surface
        }
        val textColorRes = when {
            holiday?.tone == HolidayTone.OFFICIAL -> R.color.priority_high_text
            holiday?.tone == HolidayTone.SUBSTITUTE -> R.color.brand_sky
            isWeekend -> R.color.text_secondary
            else -> R.color.text_primary
        }

        val card = MaterialCardView(requireContext()).apply {
            radius = dp(18f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), if (isSelected) R.color.brand_powder else R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), backgroundRes))
            layoutParams = LinearLayout.LayoutParams(0, dpInt(92), 1f).apply {
                marginEnd = dpInt(6)
                bottomMargin = dpInt(6)
            }
            cardElevation = dp(0f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(dayMillis) }
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(10), dpInt(10), dpInt(10), dpInt(10))
        }

        val topRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dayView = TextView(requireContext()).apply {
            text = Calendar.getInstance().apply { timeInMillis = dayMillis }.get(Calendar.DAY_OF_MONTH).toString()
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), textColorRes))
        }

        topRow.addView(dayView)
        if (isToday) {
            topRow.addView(pill("今天", R.color.brand_powder, compact = true))
        }
        if (completionCount > 0) {
            topRow.addView(pill("完成 $completionCount", R.color.brand_sky, compact = true))
        }

        val holidayView = TextView(requireContext()).apply {
            text = holiday?.label.orEmpty()
            textSize = 11f
            setTextColor(ContextCompat.getColor(requireContext(), textColorRes))
            setPadding(0, dpInt(6), 0, 0)
            maxLines = 1
            isVisible = holiday?.tone == HolidayTone.OFFICIAL || holiday?.tone == HolidayTone.SUBSTITUTE
        }

        val dotRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpInt(6), 0, 0)
        }
        val dotColors = buildList {
            val completedTasks = dayTasks.filter { it.isCompleted }
            val activeTasks = dayTasks.filterNot { it.isCompleted }
            completedTasks.take(3).forEach { add(R.color.brand_sky) }
            activeTasks
                .sortedWith(compareByDescending<TaskItem> { it.priority }.thenBy { it.dueAtMillis ?: Long.MAX_VALUE })
                .take(3 - size)
                .forEach { add(taskDotColor(it)) }
        }.take(3)
        dotColors.forEachIndexed { index, colorRes ->
            dotRow.addView(calendarDot(colorRes, startMargin = if (index > 0) 4 else 0))
        }
        val countView = TextView(requireContext()).apply {
            text = when {
                dayTasks.isNotEmpty() && completionCount > 0 -> "${dayTasks.size} 項 · $completionCount 點"
                dayTasks.isNotEmpty() -> "${dayTasks.size} 項"
                completionCount > 0 -> "$completionCount 點"
                else -> "空白"
            }
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), if (dayTasks.isNotEmpty() || completionCount > 0) textColorRes else R.color.text_secondary))
            setPadding(0, dpInt(8), 0, 0)
        }

        content.addView(topRow)
        content.addView(holidayView)
        if (dotRow.childCount > 0) {
            content.addView(dotRow)
        }
        content.addView(countView)
        card.addView(content)
        return card
    }

    private fun spacerCell(context: android.content.Context): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpInt(92), 1f).apply {
                marginEnd = dpInt(6)
                bottomMargin = dpInt(6)
            }
        }
    }

    private fun trendModeButton(label: String, mode: TrendDisplayMode): MaterialButton {
        return MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = View.generateViewId()
            tag = mode
            text = label
            isAllCaps = false
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.surface)
            )
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.card_stroke))
            strokeWidth = dpInt(1)
            cornerRadius = dpInt(999)
            minHeight = dpInt(38)
            setPadding(dpInt(14), dpInt(8), dpInt(14), dpInt(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (label != getString(R.string.data_mode_day)) leftMargin = dpInt(8)
            }
        }
    }

    private fun summaryRow(vararg items: SummaryItem): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpInt(16)
            }
        }

        items.forEachIndexed { index, item ->
            row.addView(summaryCard(item, index, items.size))
        }
        return row
    }

    private fun summaryCard(item: SummaryItem, index: Int, total: Int): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(20f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), item.backgroundColorRes))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = dpInt(10)
            }
            cardElevation = dp(0f)
        }

        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(14), dpInt(14), dpInt(14), dpInt(14))
        }

        val label = TextView(requireContext()).apply {
            text = item.label
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setTypeface(typeface, Typeface.BOLD)
        }

        val value = TextView(requireContext()).apply {
            text = item.valueText
            textSize = if (item.isPercent) 24f else 26f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dpInt(8), 0, 0)
        }

        column.addView(label)
        column.addView(value)
        card.addView(column)
        return card
    }

    private fun metricBlock(title: String, value: String, subtitle: String, accentColorRes: Int): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(24f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
            cardElevation = dp(0f)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(16))
        }

        content.addView(pill(title, accentColorRes))

        val valueView = TextView(requireContext()).apply {
            text = value
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setPadding(0, dpInt(10), 0, 0)
        }

        val subtitleView = TextView(requireContext()).apply {
            text = subtitle
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(6), 0, 0)
        }

        content.addView(valueView)
        content.addView(subtitleView)
        card.addView(content)
        return card
    }

    private fun completionRingCard(analytics: RangeAnalytics): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(24f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(16) }
            cardElevation = dp(0f)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(16))
        }

        content.addView(pill(getString(R.string.data_completion_rate_card), R.color.brand_powder))
        val rowShell = MaterialCardView(requireContext()).apply {
            radius = dp(22f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.hero_background))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
            cardElevation = dp(0f)
        }

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(16))
        }

        row.addView(RingProgressView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(108), dpInt(108))
            setAccentColor(R.color.brand_powder)
            setProgressPercent(analytics.completionRate)
        })

        val textColumn = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpInt(14)
            }
        }

        textColumn.addView(TextView(requireContext()).apply {
            text = getString(R.string.data_completion_rate)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        })
        textColumn.addView(TextView(requireContext()).apply {
            text = "${analytics.completedCount}/${analytics.totalCount} 項完成"
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(6), 0, 0)
        })
        textColumn.addView(TextView(requireContext()).apply {
            text = "進行中 ${analytics.activeCount} 項"
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(6), 0, 0)
        })
        textColumn.addView(TextView(requireContext()).apply {
            text = "${getString(R.string.data_focus_time)} ${formatMinutes(analytics.focusMinutes)}"
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(6), 0, 0)
        })

        row.addView(textColumn)
        rowShell.addView(row)
        content.addView(rowShell)
        card.addView(content)
        return card
    }

    private fun trendLineCard(series: List<DayCompletionStat>, window: AnalysisWindow): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(24f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
            cardElevation = dp(0f)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(16))
        }

        content.addView(pill(getString(R.string.data_trend_title), R.color.brand_powder))
        content.addView(TextView(requireContext()).apply {
            text = getString(R.string.data_trend_hint)
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(8), 0, 0)
        })
        content.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpInt(8), 0, 0)
            addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dpInt(10), dpInt(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999f)
                    setColor(ContextCompat.getColor(requireContext(), R.color.brand_powder))
                }
            })
            addView(TextView(requireContext()).apply {
                text = "粉色折線 = 完成數"
                textSize = 11f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                setPadding(dpInt(8), 0, 0, 0)
            })
        })

        val modeGroup = MaterialButtonToggleGroup(requireContext()).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
        }

        val modeButtons = listOf(
            trendModeButton(getString(R.string.data_mode_day), TrendDisplayMode.DAILY),
            trendModeButton(getString(R.string.data_mode_week), TrendDisplayMode.WEEKLY),
            trendModeButton(getString(R.string.data_mode_month), TrendDisplayMode.MONTHLY),
        )
        modeButtons.forEach { modeGroup.addView(it) }
        modeButtons.firstOrNull { it.tag == currentTrendDisplayMode }?.let { modeGroup.check(it.id) }
        fun refreshModeStyles() {
            modeButtons.forEach { button ->
                val selected = button.isChecked
                button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), if (selected) R.color.brand_powder else R.color.surface)
                )
                button.strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), if (selected) R.color.brand_powder else R.color.card_stroke)
                )
            }
        }
        refreshModeStyles()

        modeGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val nextMode = (group.findViewById<MaterialButton>(checkedId).tag as? TrendDisplayMode) ?: return@addOnButtonCheckedListener
            if (nextMode == currentTrendDisplayMode) return@addOnButtonCheckedListener
            currentTrendDisplayMode = nextMode
            refreshModeStyles()
            latestRangeAnalytics?.let { renderData(it) }
        }
        content.addView(modeGroup)

        content.addView(TextView(requireContext()).apply {
            text = when (currentTrendDisplayMode) {
                TrendDisplayMode.DAILY -> "粉色折線的每個節點代表一天完成數，可左右滑動看更多日子。"
                TrendDisplayMode.WEEKLY -> "粉色折線的每個節點代表一週完成數，可左右滑動看更多週次。"
                TrendDisplayMode.MONTHLY -> "粉色折線的每個節點代表一個月區段完成數，可左右滑動看更多月份。"
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(10), 0, 0)
        })

        val chartShell = MaterialCardView(requireContext()).apply {
            radius = dp(22f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.hero_background))
            cardElevation = dp(0f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpInt(272)
            ).apply { topMargin = dpInt(12) }
        }

        val chartView = TrendLineView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setAccentColor(
                when (currentTrendDisplayMode) {
                    TrendDisplayMode.DAILY -> R.color.brand_teal_dark
                    TrendDisplayMode.WEEKLY -> R.color.brand_teal_dark
                    TrendDisplayMode.MONTHLY -> R.color.brand_teal_dark
                }
            )
        }
        chartView.setSeries(series)
        val chartContainer = FrameLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dpInt(6), dpInt(6), dpInt(6), dpInt(6))
            addView(chartView)
            addView(MaterialCardView(requireContext()).apply {
                radius = dp(999f)
                strokeWidth = dpInt(1)
                strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
                setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.hero_background))
                cardElevation = dp(0f)
                layoutParams = FrameLayout.LayoutParams(
                    dpInt(26),
                    dpInt(26),
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = dpInt(4)
                    rightMargin = dpInt(4)
                }
                addView(ImageView(requireContext()).apply {
                    setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_chart_reset_brand))
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.brand_teal_dark)
                    )
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                    setPadding(dpInt(4), dpInt(4), dpInt(4), dpInt(4))
                })
                setOnClickListener { chartView.resetInteraction() }
            })
        }
        chartShell.addView(chartContainer)
        content.addView(chartShell)
        card.addView(content)
        return card
    }

    private fun ringMetricCard(title: String, percent: Int, detail: String, accentColorRes: Int): View {
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val ring = RingProgressView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(108), dpInt(108))
            setAccentColor(accentColorRes)
            setProgressPercent(percent)
        }

        val titleView = TextView(requireContext()).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            gravity = Gravity.CENTER
            setPadding(0, dpInt(10), 0, 0)
        }

        val detailView = TextView(requireContext()).apply {
            text = detail
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, dpInt(4), 0, 0)
        }

        column.addView(ring)
        column.addView(titleView)
        column.addView(detailView)
        return column
    }

    private fun taskCard(task: TaskItem, dayStart: Long? = null): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(22f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(10) }
            cardElevation = dp(0f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                (activity as? ShellActivity)?.openTaskEditor(task.id, R.id.nav_schedule)
            }
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(16))
        }

        val topRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        topRow.addView(pill(dueLabel(task), accentFor(task), compact = true))

        val titleView = TextView(requireContext()).apply {
            text = task.title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpInt(10)
            }
        }

        topRow.addView(titleView)
        content.addView(topRow)

        val detail = TextView(requireContext()).apply {
            text = buildList {
                add(task.categoryTitle.ifBlank { "未分類" })
                if (task.tags.isNotEmpty()) add("#${task.tags.take(2).joinToString(" #")}")
                if (dayStart != null) add("當天查看")
                task.notes.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" · ")
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(8), 0, 0)
            maxLines = 2
        }
        content.addView(detail)
        card.addView(content)
        return card
    }

    private fun timelineTaskRow(task: TaskItem, index: Int, total: Int, dayStart: Long): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(10) }
        }

        val rail = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dpInt(42), LinearLayout.LayoutParams.MATCH_PARENT)
        }

        rail.addView(TextView(requireContext()).apply {
            text = taskScheduleTimeLabel(task, dayStart)
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(ContextCompat.getColor(requireContext(), R.color.surface_variant))
                setStroke(dpInt(1), ContextCompat.getColor(requireContext(), R.color.card_stroke))
            }
            setPadding(dpInt(8), dpInt(4), dpInt(8), dpInt(4))
            gravity = Gravity.CENTER
        })

        rail.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(2), dpInt(10)).apply {
                topMargin = dpInt(6)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(ContextCompat.getColor(requireContext(), R.color.card_stroke))
            }
        })

        rail.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(2), dpInt(6)).apply {
                topMargin = dpInt(2)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(ContextCompat.getColor(requireContext(), R.color.surface_variant))
            }
        })

        rail.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(12), dpInt(12)).apply {
                topMargin = dpInt(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(requireContext(), accentFor(task)))
                setStroke(dpInt(2), ContextCompat.getColor(requireContext(), R.color.surface))
            }
        })

        rail.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(2), if (index < total - 1) dpInt(72) else dpInt(28)).apply {
                topMargin = dpInt(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(ContextCompat.getColor(requireContext(), R.color.card_stroke))
            }
        })

        val card = MaterialCardView(requireContext()).apply {
            radius = dp(22f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), accentFor(task))
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            cardElevation = dp(0f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                (activity as? ShellActivity)?.openTaskEditor(task.id, R.id.nav_schedule)
            }
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpInt(14), dpInt(14), dpInt(14), dpInt(14))
        }

        val accentStrip = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(4), LinearLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(ContextCompat.getColor(requireContext(), accentFor(task)))
            }
        }

        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dpInt(12), 0, 0, 0)
        }

        val topRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        topRow.addView(pill(taskScheduleTimeLabel(task, dayStart), accentFor(task), compact = true))

        val statusBadge = pill(
            if (task.isCompleted) "已完成" else "待處理",
            if (task.isCompleted) R.color.brand_sky else R.color.surface_variant,
            compact = true
        ).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dpInt(8)
        }
        topRow.addView(statusBadge)

        val title = TextView(requireContext()).apply {
            text = task.title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setPadding(0, dpInt(8), 0, 0)
        }

        val detail = TextView(requireContext()).apply {
            text = buildList {
                add(task.categoryTitle.ifBlank { "未分類" })
                if (task.tags.isNotEmpty()) add("#${task.tags.take(2).joinToString(" #")}")
            }.joinToString(" · ")
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(6), 0, 0)
            maxLines = 2
        }

        val note = TextView(requireContext()).apply {
            text = task.notes.takeIf { it.isNotBlank() }.orEmpty()
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(8), 0, 0)
            isVisible = text.isNotBlank()
            maxLines = 2
        }

        val timelineHint = TextView(requireContext()).apply {
            text = dueLabel(task)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.priority_low_text))
            setPadding(0, dpInt(8), 0, 0)
        }

        body.addView(topRow)
        body.addView(title)
        if (detail.text.isNotBlank()) body.addView(detail)
        if (note.isVisible) body.addView(note)
        body.addView(timelineHint)
        content.addView(accentStrip)
        content.addView(body)
        card.addView(content)

        row.addView(rail)
        row.addView(card)
        return row
    }

    private fun settingCard(title: String, subtitle: String): View {
        return infoCard("⚙", title, subtitle, R.color.brand_powder)
    }

    private fun infoCard(prefix: String, title: String, subtitle: String, accentColorRes: Int): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(20f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(12) }
            cardElevation = dp(0f)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpInt(16), dpInt(16), dpInt(16), dpInt(16))
            gravity = Gravity.CENTER_VERTICAL
        }

        content.addView(pill(prefix, accentColorRes, compact = true))

        val textColumn = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dpInt(12), 0, 0, 0)
        }

        val titleView = TextView(requireContext()).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        }

        val subtitleView = TextView(requireContext()).apply {
            text = subtitle
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, dpInt(6), 0, 0)
        }

        textColumn.addView(titleView)
        textColumn.addView(subtitleView)
        content.addView(textColumn)
        card.addView(content)
        return card
    }

    private fun hintCard(message: String): View {
        return TextView(requireContext()).apply {
            text = message
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(ContextCompat.getColor(requireContext(), R.color.surface_variant))
            }
            setPadding(dpInt(14), dpInt(12), dpInt(14), dpInt(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(10) }
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpInt(2), dpInt(14), dpInt(2), dpInt(8))
        }
    }

    private fun pill(text: String, backgroundRes: Int, compact: Boolean = false): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = if (compact) 11.5f else 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(ContextCompat.getColor(requireContext(), backgroundRes))
            }
            setPadding(dpInt(10), dpInt(6), dpInt(10), dpInt(6))
        }
    }

    private fun pairOf(label: String, value: Int, backgroundRes: Int): SummaryItem {
        return SummaryItem(label, value.toString(), backgroundRes, isPercent = false)
    }

    private fun pairOf(label: String, value: Int, backgroundRes: Int, isPercent: Boolean): SummaryItem {
        return SummaryItem(label, "$value%", backgroundRes, isPercent)
    }

    private fun pairOf(label: String, value: Int, backgroundRes: Int, valueText: String): SummaryItem {
        return SummaryItem(label, valueText, backgroundRes, isPercent = false)
    }

    private fun accentFor(task: TaskItem): Int {
        return when {
            task.isCompleted -> R.color.brand_powder
            task.dueAtMillis != null && task.dueAtMillis < startOfDay(System.currentTimeMillis()) -> R.color.priority_high_bg
            task.dueAtMillis != null && task.dueAtMillis <= startOfDay(System.currentTimeMillis()) + DAY_MILLIS -> R.color.priority_medium_bg
            else -> R.color.priority_low_bg
        }
    }

    private fun dueLabel(task: TaskItem): String {
        val due = task.dueAtMillis ?: return "無期限"
        val today = startOfDay(System.currentTimeMillis())
        val tomorrow = today + DAY_MILLIS
        val dateText = dateLabelFormatter.format(Date(due))
        val timeText = timeLabelFormatter.format(Date(due))
        return when {
            due < today -> if (hasExplicitTime(due)) "逾期 $dateText $timeText" else "逾期"
            due in today until tomorrow -> if (hasExplicitTime(due)) "今天 $timeText" else "今天"
            due in tomorrow until (tomorrow + DAY_MILLIS) -> if (hasExplicitTime(due)) "明天 $timeText" else "明天"
            hasExplicitTime(due) -> "$dateText $timeText"
            else -> dateText
        }
    }

    private fun taskScheduleTimeLabel(task: TaskItem, dayStart: Long): String {
        val due = task.dueAtMillis ?: return "全天"
        return if (hasExplicitTime(due)) {
            timeLabelFormatter.format(Date(due))
        } else {
            when {
                due < dayStart -> "逾期"
                due in dayStart until dayStart + DAY_MILLIS -> "全天"
                else -> dateLabelFormatter.format(Date(due))
            }
        }
    }

    private fun formatMinutes(minutes: Int): String {
        val hours = minutes / 60
        val remain = minutes % 60
        return when {
            hours > 0 && remain > 0 -> "${hours}h ${remain}m"
            hours > 0 -> "${hours}h"
            else -> "${remain}m"
        }
    }

    private fun startOfDay(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun startOfMonth(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun startOfWeek(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            while (timeInMillis > millis) {
                add(Calendar.DAY_OF_YEAR, -1)
            }
        }.timeInMillis
    }

    private fun startOfNextMonth(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun shiftMonth(millis: Long, delta: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            add(Calendar.MONTH, delta)
        }.let { startOfMonth(it.timeInMillis) }
    }

    private fun formatFullDate(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        val weekday = getString(
            when (calendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> R.string.day_monday
                Calendar.TUESDAY -> R.string.day_tuesday
                Calendar.WEDNESDAY -> R.string.day_wednesday
                Calendar.THURSDAY -> R.string.day_thursday
                Calendar.FRIDAY -> R.string.day_friday
                Calendar.SATURDAY -> R.string.day_saturday
                else -> R.string.day_sunday
            }
        )
        return "${calendar.get(Calendar.YEAR)} 年 ${calendar.get(Calendar.MONTH) + 1} 月 ${calendar.get(Calendar.DAY_OF_MONTH)} 日・$weekday"
    }

    private fun formatScheduleDateTitle(millis: Long): String {
        val holiday = holidayInfoForDay(millis)
        return holiday?.let { "${formatFullDate(millis)} · ${it.label}" } ?: formatFullDate(millis)
    }

    private fun dayStartForMonth(monthStart: Long, dayOfMonth: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = monthStart
            set(Calendar.DAY_OF_MONTH, dayOfMonth)
        }.let { startOfDay(it.timeInMillis) }
    }

    private fun mondayFirstIndex(dayOfWeek: Int): Int {
        return when (dayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }
    }

    private fun holidayInfoForDay(millis: Long): HolidayInfo? {
        return HolidayCalendarStore.infoForDay(requireContext(), millis)
    }

    private fun countHolidayDays(monthStart: Long): Int {
        return HolidayCalendarStore.countOfficialHolidayDays(requireContext(), monthStart)
    }

    private fun isWeekend(millis: Long): Boolean {
        val dayOfWeek = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
        return dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
    }

    private fun createCalendarLegend(): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(10) }
        }

        row.addView(calendarLegendItem("事件", R.color.brand_powder))
        row.addView(calendarLegendItem("完成", R.color.brand_sky))
        row.addView(calendarLegendItem("假日", R.color.priority_high_text))
        return row
    }

    private fun calendarLegendItem(label: String, colorRes: Int): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(calendarDot(colorRes))
        row.addView(TextView(requireContext()).apply {
            text = label
            textSize = 11f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(dpInt(6), 0, 0, 0)
        })
        return row
    }

    private fun calendarDot(colorRes: Int, startMargin: Int = 0): View {
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(6), dpInt(6)).apply {
                if (startMargin > 0) leftMargin = dpInt(startMargin)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(requireContext(), colorRes))
            }
        }
    }

    private fun taskDotColor(task: TaskItem): Int {
        return when {
            task.isCompleted -> R.color.brand_sky
            task.priority >= TaskItem.PRIORITY_HIGH -> R.color.priority_high_text
            task.priority == TaskItem.PRIORITY_MEDIUM -> R.color.brand_powder
            else -> R.color.priority_low_text
        }
    }

    private fun dayStatCard(label: String, value: String, backgroundRes: Int, startMargin: Int = 0): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(18f)
            strokeWidth = dpInt(1)
            strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), backgroundRes))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (startMargin > 0) leftMargin = dpInt(startMargin)
            }
            cardElevation = dp(0f)
        }

        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(12), dpInt(10), dpInt(12), dpInt(10))
        }

        column.addView(TextView(requireContext()).apply {
            text = label
            textSize = 11f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        })
        column.addView(TextView(requireContext()).apply {
            text = value
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setPadding(0, dpInt(4), 0, 0)
        })

        card.addView(column)
        return card
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun dpInt(value: Int): Int = dp(value.toFloat()).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_CALENDAR_MONTH_MILLIS, currentCalendarMonthMillis)
        outState.putLong(STATE_SELECTED_DAY_MILLIS, selectedScheduleDateMillis ?: -1L)
        outState.putInt(STATE_DATA_RANGE, currentDataRange.ordinal)
        if (this::currentAnalysisWindow.isInitialized) {
            outState.putLong(STATE_ANALYSIS_START, currentAnalysisWindow.startMillis)
            outState.putLong(STATE_ANALYSIS_END, currentAnalysisWindow.endMillisExclusive)
            outState.putInt(STATE_ANALYSIS_MODE, currentAnalysisWindow.trendBucketMode.ordinal)
            outState.putString(STATE_ANALYSIS_LABEL, currentAnalysisWindow.label)
            outState.putString(STATE_ANALYSIS_SUBTITLE, currentAnalysisWindow.subtitle)
        }
        outState.putInt(STATE_TREND_DISPLAY_MODE, currentTrendDisplayMode.ordinal)
    }

    private data class AnalysisWindow(
        val label: String,
        val subtitle: String,
        val startMillis: Long,
        val endMillisExclusive: Long,
        val trendBucketMode: TrendBucketMode,
    )

    private enum class TrendBucketMode {
        DAILY,
        WEEKLY,
        MONTHLY,
    }

    private data class RangeAnalytics(
        val window: AnalysisWindow,
        val completedCount: Int,
        val activeCount: Int,
        val totalCount: Int,
        val completionRate: Int,
        val focusMinutes: Int,
        val trend: List<DayCompletionStat>,
    )

    private enum class TrendDisplayMode {
        DAILY,
        WEEKLY,
        MONTHLY,
    }

    private data class SummaryItem(
        val label: String,
        val valueText: String,
        val backgroundColorRes: Int,
        val isPercent: Boolean,
    )

    private enum class DataRange {
        WEEK,
        MONTH,
    }

    private class RingProgressView(context: android.content.Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = context.resources.displayMetrics.density * 10f
            color = ContextCompat.getColor(context, R.color.surface_variant)
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = context.resources.displayMetrics.density * 10f
            color = ContextCompat.getColor(context, R.color.brand_powder)
        }
        private val progressTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_primary)
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                20f,
                context.resources.displayMetrics
            )
            typeface = Typeface.DEFAULT_BOLD
        }
        private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_secondary)
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                12f,
                context.resources.displayMetrics
            )
        }
        private val arcRect = RectF()
        private var progressPercent: Int = 0

        fun setAccentColor(resId: Int) {
            progressPaint.color = ContextCompat.getColor(context, resId)
            invalidate()
        }

        fun setProgressPercent(percent: Int) {
            progressPercent = percent.coerceIn(0, 100)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val strokeInset = progressPaint.strokeWidth / 2f
            arcRect.set(
                strokeInset,
                strokeInset,
                width.toFloat() - strokeInset,
                height.toFloat() - strokeInset
            )
            canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
            canvas.drawArc(arcRect, -90f, 360f * (progressPercent / 100f), false, progressPaint)

            val centerX = width / 2f
            val centerY = height / 2f
            canvas.drawText("${progressPercent}%", centerX, centerY + progressTextPaint.textSize * 0.36f, progressTextPaint)
            canvas.drawText("完成率", centerX, centerY + progressTextPaint.textSize * 1.18f, captionPaint)
        }
    }

    private class TrendLineView(context: android.content.Context) : View(context) {
        private val density = context.resources.displayMetrics.density

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.surface_variant)
            strokeWidth = density * 1f
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = ContextCompat.getColor(context, R.color.brand_powder)
        }
        private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.brand_powder)
        }
        private val pointHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.surface)
        }
        private val pointSelectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 2.25f
            color = ContextCompat.getColor(context, R.color.brand_teal_dark)
        }
        private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.surface)
        }
        private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.brand_powder).let { color ->
                Color.argb(78, Color.red(color), Color.green(color), Color.blue(color))
            }
        }
        private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 1.2f
            color = ContextCompat.getColor(context, R.color.brand_teal_dark)
        }
        private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_primary)
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                11f,
                context.resources.displayMetrics
            )
            typeface = Typeface.DEFAULT_BOLD
        }
        private val selectedBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_primary)
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                10f,
                context.resources.displayMetrics
            )
            typeface = Typeface.DEFAULT_BOLD
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_secondary)
            textAlign = Paint.Align.RIGHT
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                9.5f,
                context.resources.displayMetrics
            )
        }
        private val weekdayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_secondary)
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                10.5f,
                context.resources.displayMetrics
            )
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_primary)
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                12f,
                context.resources.displayMetrics
            )
            typeface = Typeface.DEFAULT_BOLD
        }
        private val path = Path()
        private val points = mutableListOf<DayCompletionStat>()
        private val pointCenters = mutableListOf<PointF>()
        private var selectedIndex: Int = -1
        private var zoomFactor: Float = 1f
        private var panX: Float = 0f
        private var lastPlotLeft: Float = 0f
        private var lastPlotWidth: Float = 0f
        private var isScaling: Boolean = false

        private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val previousZoom = zoomFactor
                val nextZoom = (zoomFactor * detector.scaleFactor).coerceIn(1f, 3f)
                if (previousZoom == nextZoom) return true
                val focus = detector.focusX - lastPlotLeft
                zoomFactor = nextZoom
                if (previousZoom > 0f) {
                    val zoomRatio = nextZoom / previousZoom
                    panX = ((panX + focus) * zoomRatio) - focus
                }
                clampPan()
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })
        private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (zoomFactor <= 1f && points.size <= 1) return false
                panX = (panX + distanceX).coerceIn(0f, maxPanX())
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val selected = nearestPointIndex(e.x, e.y)
                selectedIndex = when {
                    selected < 0 -> -1
                    selected == selectedIndex -> -1
                    else -> selected
                }
                invalidate()
                dispatchSelection()
                return true
            }
        })

        var onSelectionChanged: ((DayCompletionStat?) -> Unit)? = null

        fun setSeries(series: List<DayCompletionStat>) {
            points.clear()
            points.addAll(series)
            selectedIndex = if (points.isNotEmpty()) points.lastIndex else -1
            zoomFactor = 1f
            panX = 0f
            invalidate()
            dispatchSelection()
        }

        fun setAccentColor(resId: Int) {
            val accent = ContextCompat.getColor(context, resId)
            linePaint.color = accent
            pointFillPaint.color = accent
            pointSelectedStrokePaint.color = accent
            bubbleStrokePaint.color = accent
            invalidate()
        }

        fun resetInteraction() {
            zoomFactor = 1f
            panX = 0f
            clampPan()
            invalidate()
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (points.isEmpty()) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (points.isEmpty()) {
                drawEmpty(canvas)
                return
            }

            val maxValue = points.maxOfOrNull { it.completedCount }?.coerceAtLeast(1) ?: 1
            val axisTicks = buildAxisTicks(maxValue)
            val axisLabelWidth = axisTicks.maxOf { axisPaint.measureText(it.toString()) } + dp(10f)
            val axisTextRight = paddingLeft + dp(10f) + axisLabelWidth

            val left = axisTextRight + dp(34f)
            val right = width - paddingRight - dp(10f)
            val top = paddingTop + dp(26f)
            val bottom = height - paddingBottom - dp(34f)
            val plotTop = top + dp(18f)
            val plotBottom = bottom - dp(18f)
            val chartHeight = (plotBottom - plotTop).coerceAtLeast(1f)
            val chartWidth = (right - left).coerceAtLeast(1f)
            val baseStep = if (points.size <= 1) 0f else chartWidth / (points.size - 1)
            val xStep = baseStep * zoomFactor
            lastPlotLeft = left
            lastPlotWidth = chartWidth

            pointCenters.clear()
            if (zoomFactor <= 1f) {
                panX = 0f
            } else {
                clampPan()
            }

            axisTicks.forEach { tick ->
                val tickRatio = if (maxValue == 0) 0f else tick.toFloat() / maxValue.toFloat()
                val y = plotBottom - (tickRatio * chartHeight)
                gridPaint.alpha = if (tick == 0) 110 else 48
                canvas.drawLine(left, y, right, y, gridPaint)
                canvas.drawText(
                    tick.toString(),
                    axisTextRight,
                    y + axisPaint.textSize * 0.35f,
                    axisPaint
                )
            }

            path.reset()
            points.forEachIndexed { index, item ->
                val x = left + index * xStep - panX
                val normalized = item.completedCount.toFloat() / maxValue.toFloat()
                val y = plotBottom - (normalized * chartHeight)
                pointCenters.add(PointF(x, y))
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, linePaint)

            points.forEachIndexed { index, item ->
                val point = pointCenters[index]
                val x = point.x
                val y = point.y
                val bubbleText = item.label
                val bubbleWidth = maxOf(dp(38f), bubbleTextPaint.measureText(bubbleText) + dp(14f))
                val bubbleHeight = dp(20f)
                val selectedBadgeText = if (index == selectedIndex) item.completedCount.toString() else ""
                val selectedBadgeWidth = if (selectedBadgeText.isNotBlank()) {
                    maxOf(dp(24f), selectedBadgeTextPaint.measureText(selectedBadgeText) + dp(14f))
                } else {
                    0f
                }
                val selectedBadgeHeight = if (selectedBadgeText.isNotBlank()) dp(18f) else 0f
                val bubbleRect = resolveBubbleRect(
                    anchorX = x,
                    anchorY = y,
                    bubbleWidth = bubbleWidth,
                    bubbleHeight = bubbleHeight,
                    plotTop = plotTop,
                    plotBottom = plotBottom,
                )
                canvas.drawText(
                    bubbleText,
                    x,
                    bubbleRect.centerY() + bubbleTextPaint.textSize * 0.30f,
                    bubbleTextPaint
                )

                if (selectedBadgeText.isNotBlank()) {
                    val badgeRect = resolveBadgeRect(
                        anchorX = x,
                        anchorY = y,
                        bubbleRect = bubbleRect,
                        badgeWidth = selectedBadgeWidth,
                        badgeHeight = selectedBadgeHeight,
                        plotTop = plotTop,
                        plotBottom = plotBottom
                    )
                    canvas.drawRoundRect(badgeRect, dp(999f), dp(999f), badgeFillPaint)
                    canvas.drawText(
                        selectedBadgeText,
                        badgeRect.centerX(),
                        badgeRect.centerY() + selectedBadgeTextPaint.textSize * 0.30f,
                        selectedBadgeTextPaint
                    )
                }

                canvas.drawCircle(x, y, dp(7.5f), pointHaloPaint)
                if (index == selectedIndex) {
                    canvas.drawCircle(x, y, dp(5.2f), pointSelectedStrokePaint)
                    canvas.drawCircle(x, y, dp(3.4f), pointFillPaint)
                } else {
                    canvas.drawCircle(x, y, dp(4.2f), pointFillPaint)
                }

                if (item.secondaryLabel.isNotBlank()) {
                    canvas.drawText(
                        item.secondaryLabel,
                        x,
                        bottom + dp(18f),
                        weekdayPaint
                    )
                }
            }
        }

        private fun nearestPointIndex(x: Float, y: Float): Int {
            if (pointCenters.isEmpty()) return -1
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            pointCenters.forEachIndexed { index, point ->
                val dx = point.x - x
                val dy = point.y - y
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = index
                }
            }
            val threshold = dp(42f)
            return if (bestDistance <= threshold * threshold) bestIndex else -1
        }

        private fun dispatchSelection() {
            onSelectionChanged?.invoke(points.getOrNull(selectedIndex))
        }

        private fun clampPan() {
            panX = panX.coerceIn(0f, maxPanX())
        }

        private fun resolveBubbleRect(
            anchorX: Float,
            anchorY: Float,
            bubbleWidth: Float,
            bubbleHeight: Float,
            plotTop: Float,
            plotBottom: Float,
        ): RectF {
            val edgePadding = dp(6f)
            val pointSafety = dp(7.5f) + dp(6f)
            val pointRect = RectF(
                anchorX - pointSafety,
                anchorY - pointSafety,
                anchorX + pointSafety,
                anchorY + pointSafety
            )

            fun makeRect(left: Float, top: Float): RectF = RectF(left, top, left + bubbleWidth, top + bubbleHeight)
            fun fits(rect: RectF): Boolean {
                if (rect.left < paddingLeft + edgePadding) return false
                if (rect.right > width - paddingRight - edgePadding) return false
                if (rect.top < plotTop + edgePadding) return false
                if (rect.bottom > plotBottom - edgePadding) return false
                if (RectF.intersects(rect, pointRect)) return false
                if (intersectsAnyTrendSegment(rect)) return false
                return true
            }

            val gap = dp(8f)
            val candidates = listOf(
                makeRect(anchorX - bubbleWidth / 2f, anchorY - gap - bubbleHeight),
                makeRect(anchorX - bubbleWidth / 2f, anchorY - (gap * 2f) - bubbleHeight),
                makeRect(anchorX + gap, anchorY - bubbleHeight / 2f),
                makeRect(anchorX - gap - bubbleWidth, anchorY - bubbleHeight / 2f),
                makeRect(anchorX - bubbleWidth / 2f, anchorY + gap),
                makeRect(anchorX + gap, anchorY + gap),
                makeRect(anchorX - gap - bubbleWidth, anchorY + gap),
            )

            candidates.firstOrNull { fits(it) }?.let { return it }

            val fallbackLeft = (anchorX - bubbleWidth / 2f).coerceIn(
                paddingLeft + edgePadding,
                width - paddingRight - edgePadding - bubbleWidth
            )
            val fallbackTop = (anchorY - gap - bubbleHeight).coerceIn(
                plotTop + edgePadding,
                plotBottom - edgePadding - bubbleHeight
            )
            return RectF(fallbackLeft, fallbackTop, fallbackLeft + bubbleWidth, fallbackTop + bubbleHeight)
        }

        private fun resolveBadgeRect(
            anchorX: Float,
            anchorY: Float,
            bubbleRect: RectF,
            badgeWidth: Float,
            badgeHeight: Float,
            plotTop: Float,
            plotBottom: Float,
        ): RectF {
            val edgePadding = dp(6f)
            val pointSafety = dp(7.5f) + dp(6f)
            val pointRect = RectF(
                anchorX - pointSafety,
                anchorY - pointSafety,
                anchorX + pointSafety,
                anchorY + pointSafety
            )
            val bubbleSafety = RectF(
                bubbleRect.left - dp(4f),
                bubbleRect.top - dp(4f),
                bubbleRect.right + dp(4f),
                bubbleRect.bottom + dp(4f)
            )

            fun makeRect(left: Float, top: Float): RectF = RectF(left, top, left + badgeWidth, top + badgeHeight)
            fun fits(rect: RectF): Boolean {
                if (rect.left < paddingLeft + edgePadding) return false
                if (rect.right > width - paddingRight - edgePadding) return false
                if (rect.top < plotTop + edgePadding) return false
                if (rect.bottom > plotBottom - edgePadding) return false
                if (RectF.intersects(rect, pointRect)) return false
                if (RectF.intersects(rect, bubbleSafety)) return false
                if (intersectsAnyTrendSegment(rect)) return false
                return true
            }

            val gap = dp(8f)
            val sideGap = dp(10f)
            val candidates = listOf(
                makeRect(anchorX - badgeWidth / 2f, bubbleRect.top - gap - badgeHeight),
                makeRect(bubbleRect.right + sideGap, anchorY - badgeHeight / 2f),
                makeRect(bubbleRect.left - sideGap - badgeWidth, anchorY - badgeHeight / 2f),
                makeRect(anchorX - badgeWidth / 2f, bubbleRect.bottom + gap),
                makeRect(bubbleRect.right + sideGap, bubbleRect.bottom + gap),
                makeRect(bubbleRect.left - sideGap - badgeWidth, bubbleRect.bottom + gap),
            )

            candidates.firstOrNull { fits(it) }?.let { return it }

            val fallbackLeft = (anchorX - badgeWidth / 2f).coerceIn(
                paddingLeft + edgePadding,
                width - paddingRight - edgePadding - badgeWidth
            )
            val fallbackTop = (bubbleRect.top - gap - badgeHeight).coerceIn(
                plotTop + edgePadding,
                plotBottom - edgePadding - badgeHeight
            )
            return RectF(fallbackLeft, fallbackTop, fallbackLeft + badgeWidth, fallbackTop + badgeHeight)
        }

        private fun intersectsAnyTrendSegment(rect: RectF): Boolean {
            if (pointCenters.size < 2) return false
            for (index in 0 until pointCenters.lastIndex) {
                if (segmentIntersectsRect(pointCenters[index], pointCenters[index + 1], rect)) {
                    return true
                }
            }
            return false
        }

        private fun segmentIntersectsRect(start: PointF, end: PointF, rect: RectF): Boolean {
            if (rect.contains(start.x, start.y) || rect.contains(end.x, end.y)) return true
            val topLeft = PointF(rect.left, rect.top)
            val topRight = PointF(rect.right, rect.top)
            val bottomLeft = PointF(rect.left, rect.bottom)
            val bottomRight = PointF(rect.right, rect.bottom)
            return segmentsIntersect(start, end, topLeft, topRight) ||
                segmentsIntersect(start, end, topRight, bottomRight) ||
                segmentsIntersect(start, end, bottomRight, bottomLeft) ||
                segmentsIntersect(start, end, bottomLeft, topLeft)
        }

        private fun segmentsIntersect(a1: PointF, a2: PointF, b1: PointF, b2: PointF): Boolean {
            fun orientation(p: PointF, q: PointF, r: PointF): Int {
                val value = (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y)
                return when {
                    value > 0f -> 1
                    value < 0f -> -1
                    else -> 0
                }
            }

            fun onSegment(p: PointF, q: PointF, r: PointF): Boolean {
                return q.x <= maxOf(p.x, r.x) && q.x >= minOf(p.x, r.x) &&
                    q.y <= maxOf(p.y, r.y) && q.y >= minOf(p.y, r.y)
            }

            val o1 = orientation(a1, a2, b1)
            val o2 = orientation(a1, a2, b2)
            val o3 = orientation(b1, b2, a1)
            val o4 = orientation(b1, b2, a2)

            if (o1 != o2 && o3 != o4) return true
            if (o1 == 0 && onSegment(a1, b1, a2)) return true
            if (o2 == 0 && onSegment(a1, b2, a2)) return true
            if (o3 == 0 && onSegment(b1, a1, b2)) return true
            if (o4 == 0 && onSegment(b1, a2, b2)) return true
            return false
        }

        private fun maxPanX(): Float {
            if (points.size <= 1) return 0f
            val totalWidth = lastPlotWidth * zoomFactor
            return maxOf(0f, totalWidth - lastPlotWidth)
        }

        private fun buildAxisTicks(maxValue: Int): List<Int> {
            val safeMax = maxValue.coerceAtLeast(1)
            return if (safeMax <= 5) {
                (0..safeMax).toList()
            } else {
                listOf(
                    0,
                    (safeMax * 0.25f).toInt().coerceAtLeast(1),
                    (safeMax * 0.5f).toInt().coerceAtLeast(1),
                    (safeMax * 0.75f).toInt().coerceAtLeast(1),
                    safeMax
                ).distinct().sorted()
            }
        }

        private fun drawEmpty(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawText("沒有趨勢資料", cx, cy, valuePaint)
        }

        private fun dp(value: Float): Float = value * density
    }

    companion object {
        private const val ARG_KIND = "arg_kind"
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        private const val STATE_CALENDAR_MONTH_MILLIS = "state_calendar_month_millis"
        private const val STATE_SELECTED_DAY_MILLIS = "state_selected_day_millis"
        private const val STATE_DATA_RANGE = "state_data_range"
        private const val STATE_ANALYSIS_START = "state_analysis_start"
        private const val STATE_ANALYSIS_END = "state_analysis_end"
        private const val STATE_ANALYSIS_MODE = "state_analysis_mode"
        private const val STATE_ANALYSIS_LABEL = "state_analysis_label"
        private const val STATE_ANALYSIS_SUBTITLE = "state_analysis_subtitle"
        private const val STATE_TREND_DISPLAY_MODE = "state_trend_display_mode"

        fun newInstance(kind: Kind): BrandTabFragment {
            return BrandTabFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_KIND, kind.ordinal)
                }
            }
        }
    }
}
