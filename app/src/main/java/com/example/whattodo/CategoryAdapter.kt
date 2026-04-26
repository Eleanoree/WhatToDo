package com.example.whattodo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class CategoryAdapter(
    private val onCategoryClicked: (TaskCategoryItem) -> Unit,
    private val onCategoryDeleteClicked: (TaskCategoryItem) -> Unit,
) : ListAdapter<TaskCategoryItem, CategoryAdapter.CategoryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return CategoryViewHolder(view, onCategoryClicked, onCategoryDeleteClicked)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class CategoryViewHolder(
        itemView: View,
        private val onCategoryClicked: (TaskCategoryItem) -> Unit,
        private val onCategoryDeleteClicked: (TaskCategoryItem) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.categoryTitleText)
        private val keyText: TextView = itemView.findViewById(R.id.categoryKeyText)
        private val defaultBadge: TextView = itemView.findViewById(R.id.categoryDefaultBadge)
        private val editButton: MaterialButton = itemView.findViewById(R.id.editCategoryButton)
        private val deleteButton: MaterialButton = itemView.findViewById(R.id.deleteCategoryButton)

        fun bind(category: TaskCategoryItem) {
            titleText.text = category.title
            keyText.text = category.key
            defaultBadge.isVisible = category.isDefault
            itemView.setOnClickListener { onCategoryClicked(category) }
            editButton.setOnClickListener { onCategoryClicked(category) }
            deleteButton.isVisible = !category.isDefault
            deleteButton.setOnClickListener { onCategoryDeleteClicked(category) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<TaskCategoryItem>() {
        override fun areItemsTheSame(oldItem: TaskCategoryItem, newItem: TaskCategoryItem): Boolean = oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: TaskCategoryItem, newItem: TaskCategoryItem): Boolean = oldItem == newItem
    }
}
