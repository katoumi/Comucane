package com.example.comucane

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.comucane.databinding.ItemObstacleLogBinding

class LogsAdapter(private var logs: List<ObstacleLog>) :
    RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

    inner class LogViewHolder(val binding: ItemObstacleLogBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemObstacleLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]

        holder.binding.tvLogTitle.text = when (log.status) {
            "STOP" -> "Obstacle Detected — STOP"
            "CAUTION" -> "Obstacle Nearby — CAUTION"
            else -> log.status
        }

        holder.binding.tvLogDistance.text = "Distance: ${log.distanceCm} cm"
        holder.binding.tvLogTime.text = log.formattedTime()
        holder.binding.tvLogLocation.text = log.formattedLocation()
    }

    override fun getItemCount(): Int = logs.size

    fun updateLogs(newLogs: List<ObstacleLog>) {
        logs = newLogs
        notifyDataSetChanged()
    }
}