package com.example.whattodo

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class TagPickerActivity : AppCompatActivity() {
    private lateinit var repository: TaskRepository
    private lateinit var chipGroup: ChipGroup
    private lateinit var tagInput: TextInputEditText
    private lateinit var emptyText: android.view.View
    private lateinit var rootView: android.view.View

    private val selectedTags = linkedSetOf<String>()
    private var allTags: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tag_picker)

        repository = TaskRepository(this)
        rootView = findViewById(android.R.id.content)
        chipGroup = findViewById(R.id.tagChipGroup)
        tagInput = findViewById(R.id.tagInput)
        emptyText = findViewById(R.id.tagPickerEmptyText)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val incoming = intent.getStringArrayListExtra(EXTRA_SELECTED_TAGS).orEmpty()
        selectedTags.addAll(incoming)

        findViewById<MaterialButton>(R.id.addTagButton).setOnClickListener { addTagFromInput() }
        findViewById<MaterialButton>(R.id.tagPickerDoneButton).setOnClickListener { finishWithSelection() }

        lifecycleScope.launch {
            repository.tagSuggestionsFlow.collect { tags ->
                allTags = tags
                rebuildChips()
            }
        }
    }

    private fun rebuildChips() {
        val combined = (allTags + selectedTags).distinct()
        chipGroup.removeAllViews()
        emptyText.visibility = if (combined.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        combined.forEach { tag ->
            val chip = Chip(this).apply {
                text = "#$tag"
                isCheckable = true
                isChecked = selectedTags.contains(tag)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedTags.add(tag)
                    } else {
                        selectedTags.remove(tag)
                    }
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun addTagFromInput() {
        val tag = tagInput.text?.toString()?.trim().orEmpty().trimStart('#')
        if (tag.isBlank()) return
        lifecycleScope.launch {
            repository.ensureTagExists(tag)
            selectedTags.add(tag)
            tagInput.setText("")
            Snackbar.make(rootView, "#$tag", Snackbar.LENGTH_SHORT).show()
            rebuildChips()
        }
    }

    private fun finishWithSelection() {
        setResult(
            RESULT_OK,
            Intent().putStringArrayListExtra(EXTRA_SELECTED_TAGS, ArrayList(selectedTags)),
        )
        finish()
    }

    companion object {
        const val EXTRA_SELECTED_TAGS = "extra_selected_tags"
    }
}
