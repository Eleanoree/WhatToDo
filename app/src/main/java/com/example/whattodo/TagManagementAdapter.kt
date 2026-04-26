package com.example.whattodo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class TagManagementAdapter(
    private val onTagClicked: (String) -> Unit,
    private val onDeleteClicked: (String) -> Unit,
) : ListAdapter<String, TagManagementAdapter.TagViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tag_management, parent, false)
        return TagViewHolder(view, onTagClicked, onDeleteClicked)
    }

    override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class TagViewHolder(
        itemView: View,
        private val onTagClicked: (String) -> Unit,
        private val onDeleteClicked: (String) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.tagManagementNameText)
        private val keyText: TextView = itemView.findViewById(R.id.tagManagementKeyText)
        private val editButton: MaterialButton = itemView.findViewById(R.id.editTagButton)
        private val deleteButton: MaterialButton = itemView.findViewById(R.id.deleteTagButton)

        fun bind(tag: String) {
            nameText.text = tag
            keyText.text = "#$tag"
            itemView.setOnClickListener { onTagClicked(tag) }
            editButton.setOnClickListener { onTagClicked(tag) }
            deleteButton.setOnClickListener { onDeleteClicked(tag) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
    }
}
