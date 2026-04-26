package com.example.whattodo

import android.media.AudioManager
import android.media.ToneGenerator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.NumberPicker
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.launch
import java.util.Locale

class FocusFragment : Fragment(R.layout.fragment_focus) {

    private lateinit var repository: TaskRepository
    private lateinit var rootView: View
    private lateinit var phaseView: TextView
    private lateinit var timeView: TextView
    private lateinit var cycleView: TextView
    private lateinit var progressView: LinearProgressIndicator
    private lateinit var taskSelectedView: TextView
    private lateinit var planSummaryView: TextView
    private lateinit var planFocusBadgeView: TextView
    private lateinit var planBreakBadgeView: TextView
    private lateinit var planTotalBadgeView: TextView
    private lateinit var planRhythmView: FocusPlanRhythmView
    private lateinit var taskInput: MaterialAutoCompleteTextView
    private lateinit var taskAdapter: ArrayAdapter<String>
    private lateinit var planGroup: MaterialButtonToggleGroup
    private lateinit var customPlanButton: MaterialButton
    private lateinit var startPauseButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var historyButton: MaterialButton

    private var taskOptions: List<TaskItem> = emptyList()
    private var selectedTaskId: Long? = null
    private var selectedTaskTitle: String = ""
    private var selectedPlan: FocusCyclePlan = FocusCyclePlan.preset(PLAN_25)
    private var currentPhase: Phase = Phase.WORK
    private var sessionRemainingSeconds: Int = selectedPlan.totalMinutes * 60
    private var phaseRemainingSeconds: Int = selectedPlan.workMinutes * 60
    private var completedWorkBlocks: Int = 0
    private var isRunning: Boolean = false
    private var sessionStartedAtMillis: Long? = null
    private var suppressTaskTextChange = false

    private val handler = Handler(Looper.getMainLooper())
    private val toneHandler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            tick()
            if (isRunning) {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TaskRepository(requireContext())
        bindViews(view)
        setupInsets()
        setupTaskDropdown()
        restoreState(savedInstanceState)
        setupControls()
        observeDashboard()
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        stopTimer()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        toneHandler.removeCallbacksAndMessages(null)
        releaseToneGenerator()
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_TASK_ID, selectedTaskId ?: -1L)
        outState.putString(STATE_TASK_TITLE, selectedTaskTitle)
        outState.putInt(STATE_PLAN_WORK_MINUTES, selectedPlan.workMinutes)
        outState.putInt(STATE_PLAN_BREAK_MINUTES, selectedPlan.breakMinutes)
        outState.putInt(STATE_PLAN_TOTAL_MINUTES, selectedPlan.totalMinutes)
        outState.putBoolean(STATE_PLAN_IS_CUSTOM, selectedPlan.isCustom)
        outState.putInt(STATE_PHASE, currentPhase.ordinal)
        outState.putInt(STATE_SESSION_REMAINING, sessionRemainingSeconds)
        outState.putInt(STATE_PHASE_REMAINING, phaseRemainingSeconds)
        outState.putInt(STATE_COMPLETED_BLOCKS, completedWorkBlocks)
        outState.putBoolean(STATE_RUNNING, isRunning)
        outState.putLong(STATE_SESSION_STARTED_AT, sessionStartedAtMillis ?: -1L)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews(view: View) {
        rootView = view.findViewById(R.id.focusRoot)
        phaseView = view.findViewById(R.id.focusPhaseText)
        timeView = view.findViewById(R.id.focusTimeText)
        cycleView = view.findViewById(R.id.focusCycleText)
        progressView = view.findViewById(R.id.focusProgress)
        taskSelectedView = view.findViewById(R.id.focusTaskSelectedText)
        planSummaryView = view.findViewById(R.id.focusPlanSummaryText)
        planFocusBadgeView = view.findViewById(R.id.focusPlanFocusBadge)
        planBreakBadgeView = view.findViewById(R.id.focusPlanBreakBadge)
        planTotalBadgeView = view.findViewById(R.id.focusPlanTotalBadge)
        planRhythmView = view.findViewById(R.id.focusPlanRhythmView)
        taskInput = view.findViewById(R.id.focusTaskInput)
        planGroup = view.findViewById(R.id.focusPlanGroup)
        customPlanButton = view.findViewById(R.id.focusCustomPlanButton)
        startPauseButton = view.findViewById(R.id.focusStartPauseButton)
        resetButton = view.findViewById(R.id.focusResetButton)
        historyButton = view.findViewById(R.id.focusHistoryButton)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupTaskDropdown() {
        taskAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf())
        taskInput.setAdapter(taskAdapter)
        taskInput.threshold = 0
        taskInput.setOnClickListener { taskInput.showDropDown() }
        taskInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                taskInput.showDropDown()
            }
        }
        taskInput.doAfterTextChanged { editable ->
            if (suppressTaskTextChange) return@doAfterTextChanged
            resolveTaskSelection(editable?.toString().orEmpty(), fromUserTyping = true)
        }
        taskInput.setOnItemClickListener { _, _, position, _ ->
            if (position <= 0) {
                selectedTaskId = null
                selectedTaskTitle = ""
            } else {
                val task = taskOptions.getOrNull(position - 1)
                selectedTaskId = task?.id
                selectedTaskTitle = task?.title.orEmpty()
            }
            syncTaskInputText()
            updateTaskSelectionText()
        }
        syncTaskInputText()
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
        planGroup.check(R.id.focusPlan25Button)
        resetSession()
        return
    }

        selectedTaskId = savedInstanceState.getLong(STATE_TASK_ID, -1L).takeIf { it >= 0L }
        selectedTaskTitle = savedInstanceState.getString(STATE_TASK_TITLE, "").orEmpty()
        selectedPlan = FocusCyclePlan(
            workMinutes = savedInstanceState.getInt(STATE_PLAN_WORK_MINUTES, PLAN_25),
            breakMinutes = savedInstanceState.getInt(STATE_PLAN_BREAK_MINUTES, 0),
            totalMinutes = savedInstanceState.getInt(STATE_PLAN_TOTAL_MINUTES, PLAN_25),
            isCustom = savedInstanceState.getBoolean(STATE_PLAN_IS_CUSTOM, false),
        ).normalized()
        currentPhase = runCatching {
            Phase.values()[savedInstanceState.getInt(STATE_PHASE, Phase.WORK.ordinal)]
        }.getOrDefault(Phase.WORK)
        sessionRemainingSeconds = savedInstanceState.getInt(STATE_SESSION_REMAINING, selectedPlan.totalMinutes * 60)
        phaseRemainingSeconds = savedInstanceState.getInt(
            STATE_PHASE_REMAINING,
            (selectedPlan.workMinutes * 60).coerceAtMost(sessionRemainingSeconds)
        )
        completedWorkBlocks = savedInstanceState.getInt(STATE_COMPLETED_BLOCKS, 0)
        isRunning = savedInstanceState.getBoolean(STATE_RUNNING, false)
        sessionStartedAtMillis = savedInstanceState.getLong(STATE_SESSION_STARTED_AT, -1L)
            .takeIf { it >= 0L }
        if (selectedPlan.isCustom) {
            selectCustomPlanButton()
        } else {
            selectPresetPlanButton(selectedPlan.totalMinutes)
        }
        syncTaskInputText()
        updateTaskSelectionText()
    }

    private fun setupControls() {
        planGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val nextPlan = when (checkedId) {
                R.id.focusPlan50Button -> FocusCyclePlan.preset(PLAN_50)
                R.id.focusPlan120Button -> FocusCyclePlan.preset(PLAN_120)
                else -> FocusCyclePlan.preset(PLAN_25)
            }
            applyPlan(nextPlan, recordCurrentAsAbandoned = sessionStartedAtMillis != null && isRunning)
        }

        customPlanButton.setOnClickListener {
            showCustomPlanDialog()
        }

        startPauseButton.setOnClickListener {
            if (isRunning) {
                stopTimer()
            } else {
                startTimer()
            }
            updateUi()
        }

        resetButton.setOnClickListener {
            val shouldRecordAbandoned = sessionStartedAtMillis != null
            if (shouldRecordAbandoned) {
                recordCurrentSession(isCompleted = false)
            }
            stopTimer()
            resetSession()
        }

        historyButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), FocusHistoryActivity::class.java))
        }
    }

    private fun observeDashboard() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                repository.dashboardFlow.collect { dashboard ->
                    taskOptions = dashboard.tasks.filterNot { it.isCompleted }.sortedBy { it.dueAtMillis ?: Long.MAX_VALUE }
                    val labels = buildList {
                        add(getStringCompat(R.string.focus_task_none))
                        addAll(taskOptions.map { it.title })
                    }
                    taskAdapter.clear()
                    taskAdapter.addAll(labels)
                    taskAdapter.notifyDataSetChanged()
                    syncSelectedTaskIfNeeded()
                    syncTaskInputText()
                }
            }
        }
    }

    private fun syncSelectedTaskIfNeeded() {
        val task = selectedTaskId?.let { id -> taskOptions.firstOrNull { it.id == id } }
        if (task != null) {
            selectedTaskTitle = task.title
        } else if (selectedTaskId != null) {
            selectedTaskId = null
        }
        updateTaskSelectionText()
    }

    private fun startTimer() {
        if (sessionRemainingSeconds <= 0 || phaseRemainingSeconds <= 0) {
            resetSession()
        }
        if (sessionStartedAtMillis == null) {
            sessionStartedAtMillis = System.currentTimeMillis()
        }
        isRunning = true
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, 1000L)
    }

    private fun stopTimer() {
        isRunning = false
        handler.removeCallbacks(tickRunnable)
    }

    private fun resetSession() {
        isRunning = false
        currentPhase = Phase.WORK
        sessionRemainingSeconds = selectedPlan.totalMinutes * 60
        phaseRemainingSeconds = (selectedPlan.workMinutes * 60).coerceAtMost(sessionRemainingSeconds)
        completedWorkBlocks = 0
        sessionStartedAtMillis = null
        updateUi()
    }

    private fun tick() {
        sessionRemainingSeconds = (sessionRemainingSeconds - 1).coerceAtLeast(0)
        phaseRemainingSeconds = (phaseRemainingSeconds - 1).coerceAtLeast(0)

        while (phaseRemainingSeconds <= 0) {
            if (sessionRemainingSeconds <= 0) {
                finishSession()
                return
            }

            if (currentPhase == Phase.WORK) {
                completedWorkBlocks += 1
                if (selectedPlan.breakMinutes > 0) {
                    currentPhase = Phase.BREAK
                    phaseRemainingSeconds = (selectedPlan.breakMinutes * 60).coerceAtMost(sessionRemainingSeconds)
                } else {
                    currentPhase = Phase.WORK
                    phaseRemainingSeconds = (selectedPlan.workMinutes * 60).coerceAtMost(sessionRemainingSeconds)
                }
            } else {
                currentPhase = Phase.WORK
                phaseRemainingSeconds = (selectedPlan.workMinutes * 60).coerceAtMost(sessionRemainingSeconds)
            }

            if (phaseRemainingSeconds > 0) break
        }

        updateUi()
    }

    private fun finishSession() {
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        recordCurrentSession(isCompleted = true)
        playCompletionSound()
        resetSession()
        cycleView.text = getString(R.string.focus_cycle_done)
    }

    private fun playCompletionSound() {
        releaseToneGenerator()
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85).also { tone ->
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            toneHandler.postDelayed({
                runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 100) }
            }, 120L)
            toneHandler.postDelayed({ releaseToneGenerator() }, 350L)
        }
    }

    private fun releaseToneGenerator() {
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun updateUi() {
        val total = selectedPlan.totalMinutes * 60
        val remaining = sessionRemainingSeconds.coerceAtLeast(0)
        val phaseLabel = if (isRunning) {
            if (currentPhase == Phase.WORK) getString(R.string.focus_phase_focus) else getString(R.string.focus_phase_pause)
        } else {
            getString(R.string.focus_phase_pause)
        }

        phaseView.text = phaseLabel
        timeView.text = "%02d:%02d".format(remaining / 60, remaining % 60)
        progressView.max = total.coerceAtLeast(1)
        progressView.progress = (total - remaining).coerceIn(0, total.coerceAtLeast(1))
        startPauseButton.text = if (isRunning) getString(R.string.focus_pause) else getString(R.string.focus_start)
        if (selectedPlan.breakMinutes <= 0) {
            cycleView.text = getString(
                R.string.focus_cycle_no_break_summary,
                selectedPlan.workMinutes,
                selectedPlan.totalMinutes,
            )
            planSummaryView.text = getString(
                if (selectedPlan.isCustom) {
                    R.string.focus_plan_custom_no_break_summary_format
                } else {
                    R.string.focus_plan_no_break_summary_format
                },
                selectedPlan.workMinutes,
                selectedPlan.totalMinutes,
            )
        } else {
            cycleView.text = getString(
                R.string.focus_cycle_summary,
                selectedPlan.workMinutes,
                selectedPlan.breakMinutes,
                selectedPlan.totalMinutes,
            )
            planSummaryView.text = getString(
                if (selectedPlan.isCustom) {
                    R.string.focus_plan_custom_summary_format
                } else {
                    R.string.focus_plan_summary_format
                },
                selectedPlan.workMinutes,
                selectedPlan.breakMinutes,
                selectedPlan.totalMinutes,
            )
        }
        planFocusBadgeView.text = getString(R.string.focus_plan_focus_badge, selectedPlan.workMinutes)
        planTotalBadgeView.text = getString(R.string.focus_plan_total_badge, selectedPlan.totalMinutes)
        planBreakBadgeView.text = if (selectedPlan.breakMinutes <= 0) {
            getString(R.string.focus_plan_no_break_badge)
        } else {
            getString(R.string.focus_plan_break_badge, selectedPlan.breakMinutes)
        }
        planRhythmView.setPlan(selectedPlan.workMinutes, selectedPlan.breakMinutes, selectedPlan.totalMinutes)
        updateTaskSelectionText()
    }

    private fun updateTaskSelectionText() {
        taskSelectedView.text = when {
            selectedTaskId != null && selectedTaskTitle.isNotBlank() -> {
                getString(R.string.focus_task_selected_summary, selectedTaskTitle)
            }
            selectedTaskTitle.isNotBlank() -> {
                getString(R.string.focus_task_custom_summary, selectedTaskTitle)
            }
            else -> getString(R.string.focus_task_none_summary)
        }
    }

    private fun syncTaskInputText() {
        val text = when {
            selectedTaskId != null && selectedTaskTitle.isNotBlank() -> selectedTaskTitle
            selectedTaskTitle.isNotBlank() -> selectedTaskTitle
            else -> ""
        }
        suppressTaskTextChange = true
        taskInput.setText(text, false)
        suppressTaskTextChange = false
    }

    private fun resolveTaskSelection(input: String, fromUserTyping: Boolean) {
        val cleaned = input.trim()
        if (cleaned.isBlank() || cleaned == getStringCompat(R.string.focus_task_none)) {
            selectedTaskId = null
            selectedTaskTitle = ""
        } else {
            val matched = taskOptions.firstOrNull { it.title.equals(cleaned, ignoreCase = true) }
            if (matched != null) {
                selectedTaskId = matched.id
                selectedTaskTitle = matched.title
            } else {
                selectedTaskId = null
                selectedTaskTitle = cleaned
            }
        }
        if (fromUserTyping) {
            updateTaskSelectionText()
        }
    }

    private fun selectPresetPlanButton(minutes: Int) {
        when (minutes) {
            PLAN_50 -> planGroup.check(R.id.focusPlan50Button)
            PLAN_120 -> planGroup.check(R.id.focusPlan120Button)
            else -> planGroup.check(R.id.focusPlan25Button)
        }
    }

    private fun selectCustomPlanButton() {
        planGroup.clearChecked()
    }

    private fun applyPlan(plan: FocusCyclePlan, recordCurrentAsAbandoned: Boolean) {
        if (recordCurrentAsAbandoned && sessionStartedAtMillis != null && isRunning) {
            recordCurrentSession(isCompleted = false)
        }
        selectedPlan = plan.normalized()
        stopTimer()
        resetSession()
        if (selectedPlan.isCustom) {
            selectCustomPlanButton()
        } else {
            selectPresetPlanButton(selectedPlan.totalMinutes)
        }
    }

    private fun showCustomPlanDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_focus_plan, null)
        val workPicker = dialogView.findViewById<NumberPicker>(R.id.focusPlanWorkPicker)
        val breakPicker = dialogView.findViewById<NumberPicker>(R.id.focusPlanBreakPicker)
        val totalPicker = dialogView.findViewById<NumberPicker>(R.id.focusPlanTotalPicker)
        val formulaText = dialogView.findViewById<TextView>(R.id.focusPlanFormulaText)
        val optionsText = dialogView.findViewById<TextView>(R.id.focusPlanOptionsText)

        val workValues = (5..60).toList()
        val breakValues = (0..15).toList()
        var currentTotalValues = buildFocusTotalOptions(selectedPlan.workMinutes, selectedPlan.breakMinutes)

        setupPicker(workPicker, workValues, selectedPlan.workMinutes.coerceIn(workValues.first(), workValues.last()))
        setupPicker(
            breakPicker,
            breakValues,
            selectedPlan.breakMinutes.coerceIn(breakValues.first(), breakValues.last()),
            zeroLabel = getString(R.string.focus_plan_no_break_badge)
        )
        setupPicker(
            totalPicker,
            currentTotalValues,
            selectedPlan.totalMinutes.coerceIn(currentTotalValues.first(), currentTotalValues.last())
        )

        fun refreshPlanPreview(keepTotal: Int? = null) {
            val work = workValues[workPicker.value]
            val breakMinutes = breakValues[breakPicker.value]
            val nextTotalValues = buildFocusTotalOptions(work, breakMinutes)
            val preferred = keepTotal ?: currentTotalValues.getOrNull(totalPicker.value) ?: nextTotalValues.first()
            currentTotalValues = nextTotalValues
            val current = nextTotalValues.minByOrNull { kotlin.math.abs(it - preferred) } ?: nextTotalValues.first()
            setupPicker(totalPicker, nextTotalValues, current)

            val formula = if (breakMinutes <= 0) {
                "總長 = 專注 × 輪數"
            } else {
                "總長 = 專注 × 輪數 + 休息 × (輪數 - 1)"
            }
            formulaText.text = getString(R.string.focus_plan_formula, formula)
            optionsText.text = getString(
                R.string.focus_plan_options_hint,
                nextTotalValues.take(6).joinToString(" / ") { "$it 分" } + if (nextTotalValues.size > 6) " …" else ""
            )
        }

        refreshPlanPreview(selectedPlan.totalMinutes)
        workPicker.setOnValueChangedListener { _, _, _ -> refreshPlanPreview(currentTotalValues.getOrNull(totalPicker.value)) }
        breakPicker.setOnValueChangedListener { _, _, _ -> refreshPlanPreview(currentTotalValues.getOrNull(totalPicker.value)) }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.focusPlanCancelButton).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<MaterialButton>(R.id.focusPlanConfirmButton).setOnClickListener {
            val plan = FocusCyclePlan(
                workMinutes = workValues[workPicker.value],
                breakMinutes = breakValues[breakPicker.value],
                totalMinutes = currentTotalValues[totalPicker.value],
                isCustom = true,
            ).normalized()
            applyPlan(plan, recordCurrentAsAbandoned = sessionStartedAtMillis != null && isRunning)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun setupPicker(
        picker: NumberPicker,
        values: List<Int>,
        initialValue: Int,
        zeroLabel: String? = null,
    ) {
        val existingValues = picker.displayedValues
        if (existingValues != null && existingValues.size == values.size) {
            picker.displayedValues = null
        }
        picker.minValue = 0
        picker.maxValue = values.lastIndex
        picker.displayedValues = values.mapIndexed { index, value ->
            if (zeroLabel != null && value <= 0) zeroLabel else "$value 分"
        }.toTypedArray()
        picker.value = values.indexOf(initialValue).takeIf { it >= 0 } ?: 0
    }

    private fun buildFocusTotalOptions(workMinutes: Int, breakMinutes: Int): List<Int> {
        val maxMinutes = 240
        val safeWork = workMinutes.coerceAtLeast(1)
        val safeBreak = breakMinutes.coerceAtLeast(0)
        val results = mutableListOf<Int>()
        var cycles = 1
        while (cycles <= 12) {
            val total = if (safeBreak <= 0) {
                safeWork * cycles
            } else {
                safeWork * cycles + safeBreak * (cycles - 1)
            }
            if (total > maxMinutes) break
            results.add(total)
            cycles += 1
        }
        return results.distinct().ifEmpty { listOf(safeWork) }
    }

    private fun recordCurrentSession(isCompleted: Boolean) {
        val now = System.currentTimeMillis()
        val startedAt = sessionStartedAtMillis ?: now - (selectedPlan.totalMinutes * 60_000L)
        val taskId = selectedTaskId
        val taskTitle = selectedTaskTitle.ifBlank { getStringCompat(R.string.focus_task_none) }
        val plan = selectedPlan
        val blocks = completedWorkBlocks.coerceAtLeast(1)
        viewLifecycleOwner.lifecycleScope.launch {
            repository.recordFocusSession(
                FocusSessionItem(
                    id = now,
                    taskId = taskId,
                    taskTitle = taskTitle,
                    plannedMinutes = plan.totalMinutes,
                    workMinutes = plan.workMinutes,
                    breakMinutes = plan.breakMinutes,
                    cyclesCompleted = blocks,
                    startedAtMillis = startedAt,
                    completedAtMillis = now,
                    isCompleted = isCompleted,
                )
            )
        }
    }

    private fun getStringCompat(resId: Int): String {
        return if (view != null) getString(resId) else resources.getString(resId)
    }

    private enum class Phase {
        WORK,
        BREAK,
    }

    companion object {
        private const val PLAN_25 = 25
        private const val PLAN_50 = 50
        private const val PLAN_120 = 120
        private const val PLAN_5 = 5

        private const val STATE_TASK_ID = "state_task_id"
        private const val STATE_TASK_TITLE = "state_task_title"
        private const val STATE_PLAN_WORK_MINUTES = "state_plan_work_minutes"
        private const val STATE_PLAN_BREAK_MINUTES = "state_plan_break_minutes"
        private const val STATE_PLAN_TOTAL_MINUTES = "state_plan_total_minutes"
        private const val STATE_PLAN_IS_CUSTOM = "state_plan_is_custom"
        private const val STATE_PHASE = "state_phase"
        private const val STATE_SESSION_REMAINING = "state_session_remaining"
        private const val STATE_PHASE_REMAINING = "state_phase_remaining"
        private const val STATE_COMPLETED_BLOCKS = "state_completed_blocks"
        private const val STATE_RUNNING = "state_running"
        private const val STATE_SESSION_STARTED_AT = "state_session_started_at"
    }
}

class FocusPlanRhythmView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface_variant)
    }
    private val workPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.brand_powder)
    }
    private val breakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.brand_sky)
    }
    private val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.priority_medium_bg)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textAlign = Paint.Align.CENTER
        textSize = context.resources.displayMetrics.scaledDensity * 12f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = context.resources.displayMetrics.scaledDensity * 11f
    }
    private val barRect = RectF()

    private var workMinutes: Int = 25
    private var breakMinutes: Int = 5
    private var totalMinutes: Int = 120

    fun setPlan(workMinutes: Int, breakMinutes: Int, totalMinutes: Int) {
        this.workMinutes = workMinutes.coerceAtLeast(1)
        this.breakMinutes = breakMinutes.coerceAtLeast(0)
        this.totalMinutes = totalMinutes.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft + dp(8f)
        val right = width - paddingRight - dp(8f)
        val top = paddingTop + dp(10f)
        val bottom = height - paddingBottom - dp(24f)
        val chartHeight = (bottom - top).coerceAtLeast(1f)
        val columnWidth = (right - left) / 3f
        val maxValue = maxOf(workMinutes, breakMinutes, totalMinutes).coerceAtLeast(1)
        val values = listOf(workMinutes, breakMinutes, totalMinutes)
        val paints = listOf(workPaint, breakPaint, totalPaint)
        val labels = listOf("專注", "休息", "總長")

        canvas.drawRoundRect(left, bottom - dp(8f), right, bottom, dp(999f), dp(999f), trackPaint)

        values.forEachIndexed { index, value ->
            val centerX = left + columnWidth * index + columnWidth / 2f
            val barWidth = columnWidth * 0.44f
            val barHeight = (value.toFloat() / maxValue.toFloat()) * (chartHeight * 0.72f)
            val barTop = bottom - barHeight
            barRect.set(centerX - barWidth / 2f, barTop, centerX + barWidth / 2f, bottom)
            canvas.drawRoundRect(barRect, dp(999f), dp(999f), paints[index])

            canvas.drawText(
                value.toString(),
                centerX,
                barTop - dp(8f),
                valuePaint
            )
            canvas.drawText(
                labels[index],
                centerX,
                bottom + dp(12f),
                labelPaint
            )
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private data class FocusCyclePlan(
    val workMinutes: Int,
    val breakMinutes: Int,
    val totalMinutes: Int,
    val isCustom: Boolean = false,
) {
    fun normalized(): FocusCyclePlan {
        val normalizedWork = workMinutes.coerceIn(1, 180)
        val normalizedBreak = breakMinutes.coerceIn(0, 60)
        val normalizedTotal = totalMinutes.coerceAtLeast(normalizedWork).coerceIn(normalizedWork, 480)
        return copy(
            workMinutes = normalizedWork,
            breakMinutes = normalizedBreak,
            totalMinutes = normalizedTotal,
        )
    }

    companion object {
        fun preset(totalMinutes: Int): FocusCyclePlan {
            return when (totalMinutes) {
                25 -> FocusCyclePlan(
                    workMinutes = 25,
                    breakMinutes = 0,
                    totalMinutes = 25,
                    isCustom = false,
                )
                50 -> FocusCyclePlan(
                    workMinutes = 50,
                    breakMinutes = 0,
                    totalMinutes = 50,
                    isCustom = false,
                )
                120 -> FocusCyclePlan(
                    workMinutes = 25,
                    breakMinutes = 5,
                    totalMinutes = 120,
                    isCustom = false,
                )
                else -> FocusCyclePlan(
                    workMinutes = totalMinutes.coerceAtLeast(1),
                    breakMinutes = 0,
                    totalMinutes = totalMinutes.coerceAtLeast(1),
                    isCustom = false,
                )
            }.normalized()
        }
    }
}
