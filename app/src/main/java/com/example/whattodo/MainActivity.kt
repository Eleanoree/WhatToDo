package com.example.whattodo

import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class HomeFragment : Fragment(R.layout.activity_main) {
    private lateinit var repository: TaskRepository
    private lateinit var taskAdapter: TaskAdapter
    private lateinit var taskRecyclerView: RecyclerView
    private lateinit var emptyStateContainer: android.view.View
    private lateinit var emptyTitleText: TextView
    private lateinit var emptyMessageText: TextView
    private lateinit var summaryTotalValue: TextView
    private lateinit var summaryActiveValue: TextView
    private lateinit var summaryCompletedValue: TextView
    private lateinit var tasksSectionTitle: TextView
    private lateinit var searchInput: TextInputEditText
    private lateinit var clearFiltersButton: MaterialButton
    private lateinit var filterToggleButton: MaterialButton
    private lateinit var filterContentContainer: android.view.View
    private lateinit var manageCategoriesButton: MaterialButton
    private lateinit var manageTagsButton: MaterialButton
    private lateinit var statusChipGroup: ChipGroup
    private lateinit var categoryChipGroup: ChipGroup
    private lateinit var mainScrollView: NestedScrollView
    private lateinit var scrollToTopFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var rootView: android.view.View

    private val dateFormatter = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val statusChipIds = LinkedHashMap<TaskStatusFilter, Int>()
    private val categoryChipIds = LinkedHashMap<String, Int>()

    private var allTasks: List<TaskItem> = emptyList()
    private var allCategories: List<TaskCategoryItem> = emptyList()
    private var allTags: List<String> = emptyList()
    private var visibleTasks: List<TaskItem> = emptyList()
    private var searchQuery: String = ""
    private var selectedStatusFilter: TaskStatusFilter = TaskStatusFilter.ALL
    private var selectedCategoryFilterKey: String = TaskCategories.ALL_KEY
    private var filtersExpanded: Boolean = false

    private var editorSelectedTagsChipGroup: ChipGroup? = null
    private var editorSelectedTags: MutableList<String> = mutableListOf()
    private var editorTagPickerButton: MaterialButton? = null
    private var editorRepeatSummaryText: TextView? = null
    private var pendingEditorTaskId: Long? = null

    private val tagPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val selected = result.data?.getStringArrayListExtra(TagPickerActivity.EXTRA_SELECTED_TAGS).orEmpty()
        editorSelectedTags = selected.toMutableList()
        renderSelectedTags(editorSelectedTagsChipGroup, editorSelectedTags)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoreSavedState(savedInstanceState)
        bindViews(view)
        setupInsets()
        setupScrollToTopButton()
        setupTaskList()
        setupFilterControls()
        setupSearchInput()

        repository = TaskRepository(requireContext())

        manageCategoriesButton.setOnClickListener {
            startActivity(Intent(requireContext(), CategoryManagementActivity::class.java))
        }
        manageTagsButton.setOnClickListener {
            startActivity(Intent(requireContext(), TagManagementActivity::class.java))
        }

        view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.addTaskFab)
            .setOnClickListener { showTaskEditor() }

        observeRepository()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SEARCH_QUERY, searchQuery)
        outState.putString(STATE_STATUS_FILTER, selectedStatusFilter.name)
        outState.putString(STATE_CATEGORY_FILTER, selectedCategoryFilterKey)
        outState.putBoolean(STATE_FILTERS_EXPANDED, filtersExpanded)
        super.onSaveInstanceState(outState)
    }

    private fun restoreSavedState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        searchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "")
        selectedStatusFilter = runCatching {
            TaskStatusFilter.valueOf(savedInstanceState.getString(STATE_STATUS_FILTER, TaskStatusFilter.ALL.name))
        }.getOrDefault(TaskStatusFilter.ALL)
        selectedCategoryFilterKey = savedInstanceState.getString(STATE_CATEGORY_FILTER, TaskCategories.ALL_KEY)
            .orEmpty()
            .ifBlank { TaskCategories.ALL_KEY }
        filtersExpanded = savedInstanceState.getBoolean(STATE_FILTERS_EXPANDED, false)
    }

    private fun bindViews(view: View) {
        rootView = view.findViewById(R.id.main)
        mainScrollView = view.findViewById(R.id.mainScrollView)
        scrollToTopFab = view.findViewById(R.id.scrollToTopFab)
        taskRecyclerView = view.findViewById(R.id.taskRecyclerView)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        emptyTitleText = view.findViewById(R.id.emptyTitleText)
        emptyMessageText = view.findViewById(R.id.emptyMessageText)
        summaryTotalValue = view.findViewById(R.id.summaryTotalValue)
        summaryActiveValue = view.findViewById(R.id.summaryActiveValue)
        summaryCompletedValue = view.findViewById(R.id.summaryCompletedValue)
        tasksSectionTitle = view.findViewById(R.id.tasksSectionTitle)
        tasksSectionTitle.text = getString(
            R.string.tasks_section_title_count,
            visibleTasks.size,
            visibleTasks.count { !it.isCompleted },
        )
        searchInput = view.findViewById(R.id.searchInput)
        clearFiltersButton = view.findViewById(R.id.clearFiltersButton)
        filterToggleButton = view.findViewById(R.id.filterToggleButton)
        filterContentContainer = view.findViewById(R.id.filterContentContainer)
        manageCategoriesButton = view.findViewById(R.id.manageCategoriesButton)
        manageTagsButton = view.findViewById(R.id.manageTagsButton)
        statusChipGroup = view.findViewById(R.id.statusChipGroup)
        categoryChipGroup = view.findViewById(R.id.categoryChipGroup)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupScrollToTopButton() {
        fun updateVisibility(scrollY: Int) {
            val contentHeight = (mainScrollView.getChildAt(0)?.height ?: 0)
            val viewportHeight = mainScrollView.height
            val scrollRange = (contentHeight - viewportHeight).coerceAtLeast(0)
            val visibleThreshold = (scrollRange * 0.55f).toInt().coerceAtLeast(260)
            val shouldShow = scrollRange > 0 && scrollY >= visibleThreshold
            scrollToTopFab.isVisible = shouldShow
        }

        mainScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateVisibility(scrollY)
        }

        mainScrollView.post {
            updateVisibility(mainScrollView.scrollY)
        }

        scrollToTopFab.setOnClickListener {
            mainScrollView.smoothScrollTo(0, 0)
        }
    }

    fun scrollToTop() {
        if (this::mainScrollView.isInitialized) {
            mainScrollView.smoothScrollTo(0, 0)
        }
    }

    fun openTaskEditor(taskId: Long? = null) {
        if (taskId == null) {
            showTaskEditor()
            return
        }
        val task = allTasks.firstOrNull { it.id == taskId }
        if (task != null) {
            pendingEditorTaskId = null
            showTaskEditor(task)
        } else {
            pendingEditorTaskId = taskId
        }
    }

    private fun setupTaskList() {
        taskAdapter = TaskAdapter(
            onTaskClicked = { task -> showTaskEditor(task) },
            onTaskCompletedChanged = { task, isCompleted ->
                lifecycleScope.launch {
                    if (isCompleted) {
                        repository.completeTask(task)
                    } else {
                        repository.upsertTask(task.copy(isCompleted = false, completedAtMillis = null))
                    }
                }
            },
        )

        taskRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        taskRecyclerView.adapter = taskAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    deleteTask(taskAdapter.currentList[position])
                }
            }
        }).attachToRecyclerView(taskRecyclerView)
    }

    private fun setupFilterControls() {
        applyFilterPanelState(filtersExpanded)
        buildStatusChips()
        selectStatusFilter(selectedStatusFilter)

        statusChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedStatusFilter = checkedIds.firstOrNull()?.let { id ->
                statusChipGroup.findViewById<Chip>(id)?.tag as? TaskStatusFilter
            } ?: TaskStatusFilter.ALL
            refreshVisibleTasks()
        }

        categoryChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedCategoryFilterKey = checkedIds.firstOrNull()?.let { id ->
                categoryChipGroup.findViewById<Chip>(id)?.tag as? String
            }?.takeIf { it.isNotBlank() } ?: TaskCategories.ALL_KEY
            refreshVisibleTasks()
        }

        clearFiltersButton.setOnClickListener {
            searchInput.setText("")
            selectStatusFilter(TaskStatusFilter.ALL)
            selectCategoryFilter(TaskCategories.ALL_KEY)
            refreshVisibleTasks()
        }

        filterToggleButton.setOnClickListener {
            applyFilterPanelState(!filtersExpanded)
        }
    }

    private fun setupSearchInput() {
        searchInput.addTextChangedListener { editable ->
            searchQuery = editable?.toString().orEmpty()
            refreshVisibleTasks()
        }
    }

    private fun observeRepository() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    repository.dashboardFlow.collect { dashboard ->
                        allTasks = dashboard.tasks
                        allCategories = dashboard.categories
                        buildCategoryChips(allCategories)
                        selectCategoryFilter(selectedCategoryFilterKey)
                        refreshVisibleTasks()
                        pendingEditorTaskId?.let { taskId ->
                            val taskToEdit = allTasks.firstOrNull { it.id == taskId }
                            if (taskToEdit != null) {
                                pendingEditorTaskId = null
                                showTaskEditor(taskToEdit)
                            }
                        }
                    }
                }
                launch {
                    repository.tagSuggestionsFlow.collect { tags ->
                        allTags = tags
                    }
                }
            }
        }
    }

    private fun buildStatusChips() {
        statusChipGroup.removeAllViews()
        statusChipIds.clear()

        TaskStatusFilter.values().forEach { filter ->
            val chip = createFilterChip(getString(filter.labelRes))
            chip.tag = filter
            statusChipIds[filter] = chip.id
            statusChipGroup.addView(chip)
        }
    }

    private fun buildCategoryChips(categories: List<TaskCategoryItem>) {
        categoryChipGroup.removeAllViews()
        categoryChipIds.clear()

        val allChip = createFilterChip(getString(R.string.category_all)).apply {
            tag = TaskCategories.ALL_KEY
        }
        categoryChipIds[TaskCategories.ALL_KEY] = allChip.id
        categoryChipGroup.addView(allChip)

        categories.forEach { category ->
            val chip = createFilterChip(category.title).apply {
                tag = category.key
            }
            categoryChipIds[category.key] = chip.id
            categoryChipGroup.addView(chip)
        }
    }

    private fun createFilterChip(text: String): Chip {
        return Chip(requireContext()).apply {
            id = android.view.View.generateViewId()
            this.text = text
            isCheckable = true
            isClickable = true
            isFocusable = true
            setEnsureMinTouchTargetSize(true)
        }
    }

    private fun selectStatusFilter(filter: TaskStatusFilter) {
        selectedStatusFilter = filter
        statusChipIds[filter]?.let { statusChipGroup.check(it) }
    }

    private fun selectCategoryFilter(categoryKey: String) {
        val normalizedKey = if (categoryKey == TaskCategories.ALL_KEY) TaskCategories.ALL_KEY else TaskCategories.normalizeKey(categoryKey)
        selectedCategoryFilterKey = normalizedKey
        categoryChipIds[normalizedKey]?.let { categoryChipGroup.check(it) } ?: categoryChipIds[TaskCategories.ALL_KEY]?.let {
            selectedCategoryFilterKey = TaskCategories.ALL_KEY
            categoryChipGroup.check(it)
        }
    }

    private fun applyFilterPanelState(expanded: Boolean) {
        filtersExpanded = expanded
        filterContentContainer.isVisible = expanded
        filterToggleButton.text = getString(if (expanded) R.string.filters_collapse_button else R.string.filters_expand_button)
        filterToggleButton.setIconResource(
            if (expanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
        )
    }

    private fun refreshVisibleTasks() {
        visibleTasks = applyFilters(allTasks)
        taskAdapter.submitList(visibleTasks)
        tasksSectionTitle.text = getString(
            R.string.tasks_section_title_count,
            visibleTasks.size,
            visibleTasks.count { !it.isCompleted },
        )
        updateSummary()
        updateEmptyState()
        updateClearFiltersState()
    }

    private fun applyFilters(tasks: List<TaskItem>): List<TaskItem> {
        val query = searchQuery.trim().lowercase(Locale.getDefault())

        return tasks.asSequence()
            .filter { task ->
                when (selectedStatusFilter) {
                    TaskStatusFilter.ALL -> true
                    TaskStatusFilter.ACTIVE -> !task.isCompleted
                    TaskStatusFilter.COMPLETED -> task.isCompleted
                }
            }
            .filter { task ->
                selectedCategoryFilterKey == TaskCategories.ALL_KEY ||
                    TaskCategories.normalizeKey(task.categoryKey) == selectedCategoryFilterKey
            }
            .filter { task ->
                query.isBlank() || task.matchesQuery(query)
            }
            .sortedWith(
                compareBy<TaskItem> { it.isCompleted }
                    .thenBy { it.dueAtMillis == null }
                    .thenBy { it.dueAtMillis ?: Long.MAX_VALUE }
                    .thenByDescending { it.updatedAtMillis }
                    .thenByDescending { it.createdAtMillis }
            )
            .toList()
    }

    private fun TaskItem.matchesQuery(query: String): Boolean {
        val haystack = listOf(
            title,
            notes,
            categoryTitle,
            tags.joinToString(" "),
            repeatRule.summary(requireContext()),
        ).joinToString(" ").lowercase(Locale.getDefault())
        return haystack.contains(query)
    }

    private fun updateSummary() {
        summaryTotalValue.text = getString(R.string.summary_count_format, allTasks.size)
        summaryActiveValue.text = getString(R.string.summary_count_format, allTasks.count { !it.isCompleted })
        summaryCompletedValue.text = getString(R.string.summary_count_format, allTasks.count { it.isCompleted })
    }

    private fun updateEmptyState() {
        val isNoData = allTasks.isEmpty()
        val isFilteredEmpty = visibleTasks.isEmpty() && !isNoData

        emptyStateContainer.isVisible = visibleTasks.isEmpty()
        emptyTitleText.setText(if (isFilteredEmpty) R.string.empty_title_filtered else R.string.empty_title)
        emptyMessageText.setText(if (isFilteredEmpty) R.string.empty_message_filtered else R.string.empty_message)
    }

    private fun updateClearFiltersState() {
        val hasActiveFilters =
            searchQuery.isNotBlank() ||
                selectedStatusFilter != TaskStatusFilter.ALL ||
                selectedCategoryFilterKey != TaskCategories.ALL_KEY
        clearFiltersButton.isEnabled = hasActiveFilters
        clearFiltersButton.alpha = if (hasActiveFilters) 1f else 0.5f
    }

    private fun showTaskEditor(task: TaskItem? = null) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_task, null)
        val titleLayout = dialogView.findViewById<TextInputLayout>(R.id.taskTitleLayout)
        val titleInput = dialogView.findViewById<TextInputEditText>(R.id.taskTitleInput)
        val noteInput = dialogView.findViewById<TextInputEditText>(R.id.taskNoteInput)
        val categoryInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.taskCategoryInput)
        val pickTagsButton = dialogView.findViewById<MaterialButton>(R.id.pickTagsButton)
        val selectedTagsChipGroup = dialogView.findViewById<ChipGroup>(R.id.selectedTagsChipGroup)
        val dueDateButton = dialogView.findViewById<MaterialButton>(R.id.taskDueDateButton)
        val clearDueDateButton = dialogView.findViewById<MaterialButton>(R.id.taskClearDueDateButton)
        val dueDateText = dialogView.findViewById<TextView>(R.id.taskDueDateText)
        val dueTimeButton = dialogView.findViewById<MaterialButton>(R.id.taskDueTimeButton)
        val clearDueTimeButton = dialogView.findViewById<MaterialButton>(R.id.taskClearDueTimeButton)
        val dueTimeText = dialogView.findViewById<TextView>(R.id.taskDueTimeText)
        val priorityInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.taskPriorityInput)
        val repeatTypeInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.taskRepeatTypeInput)
        val weeklyRepeatContainer = dialogView.findViewById<android.view.View>(R.id.weeklyRepeatContainer)
        val monthlyRepeatContainer = dialogView.findViewById<android.view.View>(R.id.monthlyRepeatContainer)
        val repeatWeekdayChipGroup = dialogView.findViewById<ChipGroup>(R.id.repeatWeekdayChipGroup)
        val repeatOrdinalInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.taskRepeatOrdinalInput)
        val repeatDayInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.taskRepeatDayInput)
        val repeatDayOfMonthInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.taskRepeatDayOfMonthInput)
        val repeatSummaryText = dialogView.findViewById<TextView>(R.id.taskRepeatSummaryText)

        val priorityOptions = resources.getStringArray(R.array.task_priority_options)
        priorityInput.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, priorityOptions))

        val repeatTypeOptions = resources.getStringArray(R.array.repeat_type_options)
        repeatTypeInput.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, repeatTypeOptions))

        val categories = allCategories.ifEmpty {
            listOf(
                TaskCategoryItem(TaskCategories.WORK_KEY, getString(R.string.category_work), true, 10),
                TaskCategoryItem(TaskCategories.PERSONAL_KEY, getString(R.string.category_personal), true, 20),
                TaskCategoryItem(TaskCategories.STUDY_KEY, getString(R.string.category_study), true, 30),
                TaskCategoryItem(TaskCategories.HEALTH_KEY, getString(R.string.category_health), true, 40),
                TaskCategoryItem(TaskCategories.HOME_KEY, getString(R.string.category_home), true, 50),
                TaskCategoryItem(TaskCategories.SHOPPING_KEY, getString(R.string.category_shopping), true, 60),
                TaskCategoryItem(TaskCategories.OTHER_KEY, getString(R.string.category_other), true, 99),
            )
        }
        val categoryLabels = categories.map { it.title }
        categoryInput.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, categoryLabels))

        val categoryKeyToTitle = categories.associateBy({ it.key }, { it.title })

        var selectedCategoryKey = TaskCategories.normalizeKey(task?.categoryKey)
        var selectedDueDate = task?.dueAtMillis
        var selectedPriority = task?.priority ?: TaskItem.PRIORITY_MEDIUM
        var selectedRepeatRule = task?.repeatRule ?: TaskRepeatRule.None

        editorSelectedTags = task?.tags?.toMutableList() ?: mutableListOf()
        editorSelectedTagsChipGroup = selectedTagsChipGroup
        editorRepeatSummaryText = repeatSummaryText
        editorTagPickerButton = pickTagsButton

        titleInput.setText(task?.title.orEmpty())
        noteInput.setText(task?.notes.orEmpty())
        categoryInput.setText(categoryKeyToTitle[selectedCategoryKey] ?: selectedCategoryKey, false)
        priorityInput.setText(priorityOptions[selectedPriority.coerceIn(0, priorityOptions.lastIndex)], false)
        repeatTypeInput.setText(repeatTypeLabel(selectedRepeatRule), false)

        setupRepeatWeeklyChips(repeatWeekdayChipGroup, selectedRepeatRule)
        setupRepeatMonthPickers(repeatOrdinalInput, repeatDayInput, repeatDayOfMonthInput, selectedRepeatRule)
        updateRepeatVisibility(repeatTypeInput.text?.toString().orEmpty(), weeklyRepeatContainer, monthlyRepeatContainer)
        updateRepeatSummary(repeatSummaryText, selectedRepeatRule)
        renderSelectedTags(selectedTagsChipGroup, editorSelectedTags)
        updateDueDateUi(
            dueDateButton = dueDateButton,
            dueTimeButton = dueTimeButton,
            clearDueDateButton = clearDueDateButton,
            clearDueTimeButton = clearDueTimeButton,
            dueDateText = dueDateText,
            dueTimeText = dueTimeText,
            dueAtMillis = selectedDueDate,
        )

        categoryInput.setOnItemClickListener { _, _, position, _ ->
            selectedCategoryKey = categories[position.coerceIn(0, categories.lastIndex)].key
        }

        priorityInput.setOnItemClickListener { _, _, position, _ ->
            selectedPriority = position.coerceIn(0, priorityOptions.lastIndex)
        }

        repeatTypeInput.setOnItemClickListener { _, _, position, _ ->
            selectedRepeatRule = repeatRuleFromMode(
                position.coerceIn(0, repeatTypeOptions.lastIndex),
                repeatWeekdayChipGroup,
                repeatOrdinalInput,
                repeatDayInput,
                repeatDayOfMonthInput,
            )
            updateRepeatVisibility(repeatTypeInput.text?.toString().orEmpty(), weeklyRepeatContainer, monthlyRepeatContainer)
            updateRepeatSummary(repeatSummaryText, selectedRepeatRule)
        }

        repeatWeekdayChipGroup.setOnCheckedStateChangeListener { _, _ ->
            if (repeatTypeMode(repeatTypeInput.text?.toString().orEmpty()) == REPEAT_MODE_WEEKLY) {
                selectedRepeatRule = TaskRepeatRule.Weekly(selectedWeekdays(repeatWeekdayChipGroup))
                updateRepeatSummary(repeatSummaryText, selectedRepeatRule)
            }
        }

        repeatOrdinalInput.setOnItemClickListener { _, _, _, _ ->
            selectedRepeatRule = TaskRepeatRule.MonthlyWeekday(
                ordinal = selectedRepeatOrdinal(repeatOrdinalInput),
                dayOfWeek = selectedRepeatDay(repeatDayInput),
            )
            updateRepeatSummary(repeatSummaryText, selectedRepeatRule)
        }

        repeatDayInput.setOnItemClickListener { _, _, _, _ ->
            selectedRepeatRule = TaskRepeatRule.MonthlyWeekday(
                ordinal = selectedRepeatOrdinal(repeatOrdinalInput),
                dayOfWeek = selectedRepeatDay(repeatDayInput),
            )
            updateRepeatSummary(repeatSummaryText, selectedRepeatRule)
        }

        repeatDayOfMonthInput.setOnItemClickListener { _, _, _, _ ->
            selectedRepeatRule = TaskRepeatRule.MonthlyDate(selectedRepeatDayOfMonth(repeatDayOfMonthInput))
            updateRepeatSummary(repeatSummaryText, selectedRepeatRule)
        }

        pickTagsButton.setOnClickListener {
            openTagPicker(editorSelectedTags)
        }

        dueDateButton.setOnClickListener {
            val pickerBuilder = MaterialDatePicker.Builder.datePicker()
                .setTitleText(getString(R.string.task_due_date_label))
            selectedDueDate?.let { pickerBuilder.setSelection(normalizeDateOnlyMillis(it)) }
            val picker = pickerBuilder.build()
            picker.addOnPositiveButtonClickListener { selection ->
                val existingTime = selectedDueDate?.takeIf { hasExplicitTime(it) }
                    ?.let { extractTimeParts(it) }
                selectedDueDate = if (existingTime != null) {
                    combineDateAndTime(selection, existingTime.first, existingTime.second)
                } else {
                    normalizeDateOnlyMillis(selection)
                }
                updateDueDateUi(
                    dueDateButton = dueDateButton,
                    dueTimeButton = dueTimeButton,
                    clearDueDateButton = clearDueDateButton,
                    clearDueTimeButton = clearDueTimeButton,
                    dueDateText = dueDateText,
                    dueTimeText = dueTimeText,
                    dueAtMillis = selectedDueDate,
                )
            }
            picker.show(parentFragmentManager, "task_due_date")
        }

        dueTimeButton.setOnClickListener {
            val anchor = selectedDueDate ?: normalizeDateOnlyMillis(System.currentTimeMillis())
            val (hour, minute) = extractTimeParts(anchor)
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .setTitleText(getString(R.string.task_due_time_label))
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedDueDate = combineDateAndTime(selectedDueDate ?: anchor, picker.hour, picker.minute)
                updateDueDateUi(
                    dueDateButton = dueDateButton,
                    dueTimeButton = dueTimeButton,
                    clearDueDateButton = clearDueDateButton,
                    clearDueTimeButton = clearDueTimeButton,
                    dueDateText = dueDateText,
                    dueTimeText = dueTimeText,
                    dueAtMillis = selectedDueDate,
                )
            }
            picker.show(parentFragmentManager, "task_due_time")
        }

        clearDueDateButton.setOnClickListener {
            selectedDueDate = null
            updateDueDateUi(
                dueDateButton = dueDateButton,
                dueTimeButton = dueTimeButton,
                clearDueDateButton = clearDueDateButton,
                clearDueTimeButton = clearDueTimeButton,
                dueDateText = dueDateText,
                dueTimeText = dueTimeText,
                dueAtMillis = selectedDueDate,
            )
        }

        clearDueTimeButton.setOnClickListener {
            selectedDueDate = selectedDueDate?.let { normalizeDateOnlyMillis(it) }
            updateDueDateUi(
                dueDateButton = dueDateButton,
                dueTimeButton = dueTimeButton,
                clearDueDateButton = clearDueDateButton,
                clearDueTimeButton = clearDueTimeButton,
                dueDateText = dueDateText,
                dueTimeText = dueTimeText,
                dueAtMillis = selectedDueDate,
            )
        }

        val alertDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        dialogView.findViewById<TextView>(R.id.taskDialogBadgeText).text = getString(
            if (task == null) R.string.task_dialog_badge_new else R.string.task_dialog_badge_edit
        ).let { "🐾 $it" }

        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.taskDialogCancelButton)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.taskDialogSaveButton)

        cancelButton.setOnClickListener {
            alertDialog.dismiss()
        }

        saveButton.setOnClickListener {
                val title = titleInput.text?.toString()?.trim().orEmpty()
                if (title.isBlank()) {
                    titleLayout.error = getString(R.string.task_title_required)
                    return@setOnClickListener
                }
                titleLayout.error = null

                val note = noteInput.text?.toString()?.trim().orEmpty()
                val categoryLabel = categoryInput.text?.toString().orEmpty()
                val resolvedCategoryKey = categories.firstOrNull { it.title == categoryLabel }?.key ?: selectedCategoryKey
                val repeatRule = repeatRuleFromMode(
                    repeatTypeMode(repeatTypeInput.text?.toString().orEmpty()),
                    repeatWeekdayChipGroup,
                    repeatOrdinalInput,
                    repeatDayInput,
                    repeatDayOfMonthInput,
                )

                val now = System.currentTimeMillis()
                val updatedTask = if (task == null) {
                    TaskItem(
                        id = generateTaskId(),
                        title = title,
                        notes = note,
                        categoryKey = resolvedCategoryKey,
                        categoryTitle = categoryKeyToTitle[resolvedCategoryKey].orEmpty(),
                        tags = editorSelectedTags.toList(),
                        dueAtMillis = selectedDueDate,
                        priority = selectedPriority,
                        repeatRule = repeatRule,
                        isCompleted = false,
                        createdAtMillis = now,
                        updatedAtMillis = now,
                    )
                } else {
                    task.copy(
                        title = title,
                        notes = note,
                        categoryKey = resolvedCategoryKey,
                        categoryTitle = categoryKeyToTitle[resolvedCategoryKey].orEmpty(),
                        tags = editorSelectedTags.toList(),
                        dueAtMillis = selectedDueDate,
                        priority = selectedPriority,
                        repeatRule = repeatRule,
                        updatedAtMillis = now,
                    )
                }

                lifecycleScope.launch {
                    repository.upsertTask(updatedTask)
                    alertDialog.dismiss()
                }
        }

        alertDialog.setOnDismissListener {
            editorSelectedTagsChipGroup = null
            editorSelectedTags = mutableListOf()
            editorTagPickerButton = null
            editorRepeatSummaryText = null
            (activity as? ShellActivity)?.onTaskEditorClosed()
        }

        alertDialog.show()
    }

    private fun setupRepeatWeeklyChips(chipGroup: ChipGroup, repeatRule: TaskRepeatRule) {
        chipGroup.removeAllViews()
        val selected = (repeatRule as? TaskRepeatRule.Weekly)?.daysOfWeek ?: emptySet()
        dayOfWeekOptions().forEach { option ->
            val chip = Chip(requireContext()).apply {
                text = option.label
                isCheckable = true
                isChecked = selected.contains(option.calendarConstant)
            }
            chipGroup.addView(chip)
        }
    }

    private fun setupRepeatMonthPickers(
        ordinalInput: MaterialAutoCompleteTextView,
        dayInput: MaterialAutoCompleteTextView,
        dayOfMonthInput: MaterialAutoCompleteTextView,
        repeatRule: TaskRepeatRule,
    ) {
        val ordinalOptions = monthOrdinalLabels()
        val dayOptions = dayOfWeekOptions().map { it.label }
        val dayOfMonthOptions = dayOfMonthLabels()
        ordinalInput.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, ordinalOptions))
        dayInput.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, dayOptions))
        dayOfMonthInput.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, dayOfMonthOptions))

        when (repeatRule) {
            is TaskRepeatRule.MonthlyWeekday -> {
                ordinalInput.setText(monthOrdinalLabel(repeatRule.ordinal), false)
                dayInput.setText(dayLabel(repeatRule.dayOfWeek), false)
                dayOfMonthInput.setText(dayOfMonthOptions.first(), false)
            }
            is TaskRepeatRule.MonthlyDate -> {
                ordinalInput.setText(ordinalOptions.first(), false)
                dayInput.setText(dayOptions.first(), false)
                dayOfMonthInput.setText(repeatRule.dayOfMonth.toString(), false)
            }
            else -> {
                ordinalInput.setText(ordinalOptions.first(), false)
                dayInput.setText(dayOptions.first(), false)
                dayOfMonthInput.setText(dayOfMonthOptions.first(), false)
            }
        }
    }

    private fun updateRepeatVisibility(
        repeatTypeText: String,
        weeklyContainer: android.view.View,
        monthlyContainer: android.view.View,
    ) {
        val mode = repeatTypeMode(repeatTypeText)
        weeklyContainer.isVisible = mode == REPEAT_MODE_WEEKLY
        monthlyContainer.isVisible = mode == REPEAT_MODE_MONTHLY_WEEKDAY || mode == REPEAT_MODE_MONTHLY_DATE
    }

    private fun updateRepeatSummary(textView: TextView, repeatRule: TaskRepeatRule) {
        textView.text = if (repeatRule is TaskRepeatRule.None) {
            getString(R.string.repeat_next_occurrence)
        } else {
            getString(R.string.repeat_label_prefix, repeatRule.summary(requireContext()))
        }
    }

    private fun selectedWeekdays(chipGroup: ChipGroup): Set<Int> {
        val result = linkedSetOf<Int>()
        repeat(chipGroup.childCount) { index ->
            val child = chipGroup.getChildAt(index) as? Chip ?: return@repeat
            if (child.isChecked) {
                result += dayOfWeekOptions()[index].calendarConstant
            }
        }
        return result.ifEmpty { setOf(Calendar.SATURDAY) }
    }

    private fun selectedRepeatOrdinal(input: MaterialAutoCompleteTextView): Int {
        return when (input.text?.toString().orEmpty()) {
            getString(R.string.repeat_monthly_first) -> 1
            getString(R.string.repeat_monthly_second) -> 2
            getString(R.string.repeat_monthly_third) -> 3
            getString(R.string.repeat_monthly_fourth) -> 4
            getString(R.string.repeat_monthly_last) -> -1
            else -> 1
        }
    }

    private fun selectedRepeatDay(input: MaterialAutoCompleteTextView): Int {
        return dayOfWeekOptions().firstOrNull { it.label == input.text?.toString().orEmpty() }?.calendarConstant
            ?: Calendar.FRIDAY
    }

    private fun selectedRepeatDayOfMonth(input: MaterialAutoCompleteTextView): Int {
        return input.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 31) ?: 1
    }

    private fun repeatTypeMode(repeatTypeText: String): Int {
        return when (repeatTypeText) {
            getString(R.string.repeat_daily) -> REPEAT_MODE_DAILY
            getString(R.string.repeat_weekly) -> REPEAT_MODE_WEEKLY
            getString(R.string.repeat_monthly_weekday) -> REPEAT_MODE_MONTHLY_WEEKDAY
            getString(R.string.repeat_monthly_date) -> REPEAT_MODE_MONTHLY_DATE
            getString(R.string.repeat_monthly_last_workday) -> REPEAT_MODE_MONTHLY_LAST_WORKDAY
            else -> REPEAT_MODE_NONE
        }
    }

    private fun repeatTypeLabel(repeatRule: TaskRepeatRule): String {
        return when (repeatRule) {
            TaskRepeatRule.Daily -> getString(R.string.repeat_daily)
            is TaskRepeatRule.Weekly -> getString(R.string.repeat_weekly)
            is TaskRepeatRule.MonthlyWeekday -> getString(R.string.repeat_monthly_weekday)
            is TaskRepeatRule.MonthlyDate -> getString(R.string.repeat_monthly_date)
            TaskRepeatRule.MonthlyLastWorkday -> getString(R.string.repeat_monthly_last_workday)
            TaskRepeatRule.None -> getString(R.string.repeat_none)
        }
    }

    private fun repeatRuleFromMode(
        mode: Int,
        weeklyChipGroup: ChipGroup,
        ordinalInput: MaterialAutoCompleteTextView,
        dayInput: MaterialAutoCompleteTextView,
        dayOfMonthInput: MaterialAutoCompleteTextView,
    ): TaskRepeatRule {
        return when (mode) {
            REPEAT_MODE_DAILY -> TaskRepeatRule.Daily
            REPEAT_MODE_WEEKLY -> TaskRepeatRule.Weekly(selectedWeekdays(weeklyChipGroup))
            REPEAT_MODE_MONTHLY_WEEKDAY -> TaskRepeatRule.MonthlyWeekday(
                ordinal = selectedRepeatOrdinal(ordinalInput),
                dayOfWeek = selectedRepeatDay(dayInput),
            )
            REPEAT_MODE_MONTHLY_DATE -> TaskRepeatRule.MonthlyDate(selectedRepeatDayOfMonth(dayOfMonthInput))
            REPEAT_MODE_MONTHLY_LAST_WORKDAY -> TaskRepeatRule.MonthlyLastWorkday
            else -> TaskRepeatRule.None
        }
    }

    private fun monthOrdinalLabels(): List<String> {
        return listOf(
            getString(R.string.repeat_monthly_first),
            getString(R.string.repeat_monthly_second),
            getString(R.string.repeat_monthly_third),
            getString(R.string.repeat_monthly_fourth),
            getString(R.string.repeat_monthly_last),
        )
    }

    private fun monthOrdinalLabel(ordinal: Int): String {
        return when (ordinal) {
            1 -> getString(R.string.repeat_monthly_first)
            2 -> getString(R.string.repeat_monthly_second)
            3 -> getString(R.string.repeat_monthly_third)
            4 -> getString(R.string.repeat_monthly_fourth)
            -1 -> getString(R.string.repeat_monthly_last)
            else -> getString(R.string.repeat_monthly_first)
        }
    }

    private fun dayOfMonthLabels(): List<String> {
        return (1..31).map { it.toString() }
    }

    private fun dayLabel(calendarConstant: Int): String {
        return dayOfWeekOptions().firstOrNull { it.calendarConstant == calendarConstant }?.label
            ?: getString(R.string.day_friday)
    }

    private fun dayOfWeekOptions(): List<DayOption> {
        return listOf(
            DayOption(Calendar.SUNDAY, getString(R.string.day_sunday)),
            DayOption(Calendar.MONDAY, getString(R.string.day_monday)),
            DayOption(Calendar.TUESDAY, getString(R.string.day_tuesday)),
            DayOption(Calendar.WEDNESDAY, getString(R.string.day_wednesday)),
            DayOption(Calendar.THURSDAY, getString(R.string.day_thursday)),
            DayOption(Calendar.FRIDAY, getString(R.string.day_friday)),
            DayOption(Calendar.SATURDAY, getString(R.string.day_saturday)),
        )
    }

    private fun renderSelectedTags(chipGroup: ChipGroup?, tags: List<String>) {
        chipGroup?.removeAllViews()
        chipGroup?.isVisible = tags.isNotEmpty()
        tags.forEach { tag ->
            chipGroup?.addView(
                Chip(requireContext()).apply {
                    text = "#$tag"
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        editorSelectedTags.remove(tag)
                        renderSelectedTags(editorSelectedTagsChipGroup, editorSelectedTags)
                    }
                },
            )
        }
    }

    private fun openTagPicker(selected: List<String>) {
        tagPickerLauncher.launch(
            Intent(requireContext(), TagPickerActivity::class.java)
                .putStringArrayListExtra(TagPickerActivity.EXTRA_SELECTED_TAGS, ArrayList(selected)),
        )
    }

    private fun updateDueDateUi(
        dueDateButton: MaterialButton,
        dueTimeButton: MaterialButton,
        clearDueDateButton: MaterialButton,
        clearDueTimeButton: MaterialButton,
        dueDateText: TextView,
        dueTimeText: TextView,
        dueAtMillis: Long?,
    ) {
        if (dueAtMillis == null) {
            dueDateButton.setText(R.string.task_due_date_pick)
            dueTimeButton.setText(R.string.task_due_time_pick)
            dueDateText.setText(R.string.task_due_date_empty)
            dueTimeText.setText(R.string.task_due_time_empty)
            clearDueDateButton.isEnabled = false
            clearDueDateButton.alpha = 0.5f
            clearDueTimeButton.isEnabled = false
            clearDueTimeButton.alpha = 0.5f
            dueTimeButton.isEnabled = false
            dueTimeButton.alpha = 0.5f
        } else {
            val hasTime = hasExplicitTime(dueAtMillis)
            dueDateButton.setText(R.string.task_due_date_change)
            dueTimeButton.setText(if (hasTime) R.string.task_due_time_change else R.string.task_due_time_pick)
            dueDateText.text = if (hasTime) {
                getString(
                    R.string.due_datetime_prefix,
                    dateFormatter.format(Date(dueAtMillis)),
                    timeFormatter.format(Date(dueAtMillis)),
                )
            } else {
                getString(R.string.due_date_prefix, dateFormatter.format(Date(dueAtMillis)))
            }
            dueTimeText.text = if (hasTime) {
                getString(R.string.due_time_prefix, timeFormatter.format(Date(dueAtMillis)))
            } else {
                getString(R.string.task_due_time_empty)
            }
            clearDueDateButton.isEnabled = true
            clearDueDateButton.alpha = 1f
            clearDueTimeButton.isEnabled = hasTime
            clearDueTimeButton.alpha = if (hasTime) 1f else 0.5f
            dueTimeButton.isEnabled = true
            dueTimeButton.alpha = 1f
        }
    }

    private fun deleteTask(task: TaskItem) {
        lifecycleScope.launch {
            repository.deleteTask(task.id)
            Snackbar.make(rootView, getString(R.string.task_deleted, task.title), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) {
                    lifecycleScope.launch {
                        repository.upsertTask(task)
                    }
                }
                .show()
        }
    }

    private fun generateTaskId(): Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE

    private data class DayOption(val calendarConstant: Int, val label: String)

    companion object {
        private const val STATE_SEARCH_QUERY = "state_search_query"
        private const val STATE_STATUS_FILTER = "state_status_filter"
        private const val STATE_CATEGORY_FILTER = "state_category_filter"
        private const val STATE_FILTERS_EXPANDED = "state_filters_expanded"
        private const val REPEAT_MODE_NONE = 0
        private const val REPEAT_MODE_DAILY = 1
        private const val REPEAT_MODE_WEEKLY = 2
        private const val REPEAT_MODE_MONTHLY_WEEKDAY = 3
        private const val REPEAT_MODE_MONTHLY_DATE = 4
        private const val REPEAT_MODE_MONTHLY_LAST_WORKDAY = 5
    }
}
