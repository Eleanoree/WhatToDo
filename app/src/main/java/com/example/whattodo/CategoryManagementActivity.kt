package com.example.whattodo

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class CategoryManagementActivity : AppCompatActivity() {
    private lateinit var repository: TaskRepository
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CategoryAdapter
    private lateinit var rootView: android.view.View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_category_management)

        repository = TaskRepository(this)
        rootView = findViewById(android.R.id.content)
        recyclerView = findViewById(R.id.categoryRecyclerView)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        adapter = CategoryAdapter(
            onCategoryClicked = { category -> showCategoryDialog(category) },
            onCategoryDeleteClicked = { category -> deleteCategory(category) },
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.addCategoryFab)
            .setOnClickListener { showCategoryDialog(null) }

        lifecycleScope.launch {
            repository.categoriesFlow.collect { categories ->
                adapter.submitList(
                    categories.sortedWith(
                        compareBy<TaskCategoryItem> { !it.isDefault }
                            .thenBy { it.sortOrder }
                            .thenBy { it.title }
                    ),
                )
            }
        }
    }

    private fun showCategoryDialog(category: TaskCategoryItem?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_category, null)
        val titleLayout = dialogView.findViewById<TextInputLayout>(R.id.categoryTitleLayout)
        val titleInput = dialogView.findViewById<TextInputEditText>(R.id.categoryTitleInput)

        titleInput.setText(category?.title.orEmpty())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (category == null) R.string.new_category else R.string.edit_categories)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_task, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text?.toString()?.trim().orEmpty()
                if (title.isBlank()) {
                    titleLayout.error = getString(R.string.task_title_required)
                    return@setOnClickListener
                }
                titleLayout.error = null
                lifecycleScope.launch {
                    if (category == null) {
                        repository.ensureCustomCategory(title)
                    } else {
                        repository.upsertCategory(category.copy(title = title))
                    }
                    Snackbar.make(rootView, getString(R.string.category_saved, title), Snackbar.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun deleteCategory(category: TaskCategoryItem) {
        lifecycleScope.launch {
            if (category.isDefault) {
                Snackbar.make(rootView, R.string.category_delete_blocked, Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            repository.deleteCategory(category.key)
            Snackbar.make(rootView, getString(R.string.category_deleted, category.title), Snackbar.LENGTH_SHORT).show()
        }
    }
}
