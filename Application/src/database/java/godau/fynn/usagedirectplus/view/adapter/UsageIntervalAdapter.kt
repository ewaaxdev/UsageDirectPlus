package godau.fynn.usagedirectplus.view.adapter

import android.content.Context
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import godau.fynn.usagedirectplus.R
import godau.fynn.usagedirectplus.databinding.RowUsageIntervalBinding
import godau.fynn.usagedirectplus.persistence.UsageInterval
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsageIntervalAdapter(
    private val intervals: List<UsageInterval>
) : RecyclerView.Adapter<UsageIntervalAdapter.ViewHolder>() {

    class ViewHolder(val binding: RowUsageIntervalBinding) : RecyclerView.ViewHolder(binding.root)

    private var preciseDateFormat: SimpleDateFormat? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = RowUsageIntervalBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.itemView.context
        val interval = intervals[position]
        val durationMs = interval.endTime - interval.beginTime

        // Short sessions are reported with seconds so they never read as "0 min"
        holder.binding.timeRange.text = if (durationMs < 60_000L) {
            val format = preciseDateFormat ?: SimpleDateFormat(
                DateFormat.getBestDateTimePattern(
                    Locale.getDefault(),
                    if (DateFormat.is24HourFormat(context)) "Hms" else "hms"
                ),
                Locale.getDefault()
            ).also { preciseDateFormat = it }
            "${format.format(Date(interval.beginTime))} \u2013 ${format.format(Date(interval.endTime))}"
        } else {
            val format = DateFormat.getTimeFormat(context)
            "${format.format(Date(interval.beginTime))} \u2013 ${format.format(Date(interval.endTime))}"
        }

        holder.binding.duration.text = formatDuration(context, durationMs)
    }

    override fun getItemCount(): Int = intervals.size

    companion object {

        fun formatDuration(context: Context, durationMs: Long): String {
            if (durationMs < 60_000L) {
                val seconds = maxOf(1L, (durationMs + 500L) / 1000L)
                return context.getString(R.string.usage_duration_seconds, seconds)
            }
            val totalMinutes = (durationMs + 30_000L) / 60_000L
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            return if (hours > 0L) {
                context.getString(R.string.recap_duration_hours, hours, minutes)
            } else {
                context.getString(R.string.recap_duration_minutes, minutes)
            }
        }

        fun formatTime(context: Context, timeMillis: Long): String =
            DateFormat.getTimeFormat(context).format(Date(timeMillis))
    }
}
