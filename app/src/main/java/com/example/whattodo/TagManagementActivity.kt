package com.example.whattodo

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class TagManagementActivity : AppCompatActivity() {
    private lateinit var repository: TaskRepository
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TagManagementAdapter
    private lateinit var emptyText: android.view.View
    private lateinit var input: TextInputEditText
    private lateinit var inputLayout: TextInputLayout
    private lateinit var rootView: android.view.View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tag_management)

        repository = TaskRepository(this)
        rootView = findViewById(android.R.id.content)
        recyclerView = findViewById(R.id.tagManagementRecyclerView)
        emptyText = findViewById(R.id.tagManagementEmptyText)
        inputLayout = findViewById(R.id.tagManagementInputLayout)
        input = findViewById(R.id.tagManagementInput)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        adapter = TagManagementAdapter(
            onTagClicked = { tag -> showRenameDialog(tag) },
            onDeleteClicked = { tag -> confirmDelete(tag) },
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<com.google.android.material.button.MaterialButton>(R.id.addManagedTagButton)
            .setOnClickListener { addTagFromInput() }

        lifecycleScope.launch {
            repository.tagSuggestionsFlow.collect { tags ->
                adapter.submitList(tags)
                emptyText.isVisible = tags.isEmpty()
                recyclerView.isVisible = tags.isNotEmpty()
            }
        }
    }

    private fun addTagFromInput() {
        val tag = input.text?.toString()?.trim().orEmpty().trimStart('#')
        if (tag.isBlank()) {
            inputLayout.error = getString(R.string.tag_name_required)
            return
        }
        inputLayout.error = null
        lifecycleScope.launch {
            repository.ensureTagExists(tag)
            input.setText("")
            Snackbar.make(rootView, "#$tag", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showRenameDialog(tag: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tag, null)
        val layout = dialogView.findViewById<TextInputLayout>(R.id.tagNameLayout)
        val edit = dialogView.findViewById<TextInputEditText>(R.id.tagNameInput)
        edit.setText(tag)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_management_rename_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_task, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val newName = edit.text?.toString()?.trim().orEmpty().trimStart('#')
                if (newName.isBlank()) {
                    layout.error = getString(R.string.tag_name_required)
                    return@setOnClickListener
                }
                layout.error = null
                lifecycleScope.launch {
                    repository.renameTag(tag, newName)
                    Snackbar.make(rootView, getString(R.string.tag_management_saved, newName), Snackbar.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun confirmDelete(tag: String) {
        lifecycleScope.launch {
            val usageCount = repository.countTagUsage(tag)
            val message = if (usageCount > 0) {
                getString(R.string.tag_management_delete_confirm_count, usageCount)
            } else {
                getString(R.string.tag_management_delete_confirm, tag)
            }

            MaterialAlertDialogBuilder(this@TagManagementActivity)
                .setTitle(R.string.tag_management_delete)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.tag_management_delete) { _, _ ->
                    lifecycleScope.launch {
                        repository.deleteTag(tag)
                        Snackbar.make(rootView, getString(R.string.tag_management_deleted, tag), Snackbar.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }
}
