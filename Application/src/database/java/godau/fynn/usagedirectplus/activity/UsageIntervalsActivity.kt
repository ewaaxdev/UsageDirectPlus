package godau.fynn.usagedirectplus.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import godau.fynn.usagedirectplus.R
import godau.fynn.usagedirectplus.databinding.ActivityUsageIntervalsBinding
import godau.fynn.usagedirectplus.persistence.HistoryDatabase
import godau.fynn.usagedirectplus.persistence.Note
import godau.fynn.usagedirectplus.persistence.UsageInterval
import godau.fynn.usagedirectplus.thread.icon.IconThread
import godau.fynn.usagedirectplus.view.adapter.UsageIntervalAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class UsageIntervalsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityUsageIntervalsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val applicationId = intent.getStringExtra("applicationId")!!
        val day = intent.getLongExtra("day", 0)

        // Set action bar title to app name
        val appName = IconThread.nameMap[applicationId]
        if (supportActionBar != null) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            if (appName != null) {
                supportActionBar!!.title = appName
            } else {
                supportActionBar!!.title = applicationId
            }
            val date = LocalDate.ofEpochDay(day)
            supportActionBar!!.subtitle =
                date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }

        // Set app icon
        val icon = IconThread.iconMap[applicationId]
        if (icon != null) {
            binding.appIcon.setImageDrawable(icon)
        } else {
            binding.appIcon.visibility = View.GONE
        }

        binding.notesCard.setOnClickListener {
            startActivity(
                Intent(this, NotesActivity::class.java)
                    .putExtra(NotesActivity.EXTRA_APPLICATION_ID, applicationId)
                    .putExtra(NotesActivity.EXTRA_DAY, day)
            )
        }

        binding.recyclerview.layoutManager = LinearLayoutManager(this)

        // Compute day boundaries
        val date = LocalDate.ofEpochDay(day)
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        lifecycleScope.launch(Dispatchers.IO) {
            val database = HistoryDatabase.get(this@UsageIntervalsActivity)
            val intervals: List<UsageInterval>
            val notes: List<Note>
            try {
                intervals = database.getUsageIntervalDao()
                    .getByAppAndTimeRange(applicationId, start, end)
                notes = database.getNotesDao()
                    .getByAppAndDay(applicationId, day)
            } finally {
                database.close()
            }

            withContext(Dispatchers.Main) {
                if (intervals.isEmpty()) {
                    binding.totalTime.text =
                        UsageIntervalAdapter.formatDuration(this@UsageIntervalsActivity, 0L)
                    binding.sessionMeta.text = getString(R.string.usage_no_sessions)
                    binding.sessionsHeaderCount.text = ""
                    binding.recyclerview.visibility = View.GONE
                    binding.emptyMessage.visibility = View.VISIBLE
                } else {
                    var totalMs = 0L
                    for (interval in intervals) {
                        totalMs += interval.endTime - interval.beginTime
                    }
                    binding.totalTime.text =
                        UsageIntervalAdapter.formatDuration(this@UsageIntervalsActivity, totalMs)

                    val first = intervals.minOf { it.beginTime }
                    val last = intervals.maxOf { it.endTime }
                    binding.sessionMeta.text = resources.getQuantityString(
                        R.plurals.usage_session_meta,
                        intervals.size,
                        intervals.size,
                        UsageIntervalAdapter.formatTime(this@UsageIntervalsActivity, first),
                        UsageIntervalAdapter.formatTime(this@UsageIntervalsActivity, last)
                    )
                    binding.sessionsHeaderCount.text = intervals.size.toString()

                    binding.recyclerview.adapter = UsageIntervalAdapter(intervals)
                }

                binding.dayStrip.setDay(start, end, intervals)

                if (notes.isEmpty()) {
                    binding.notesPreview.text = getString(R.string.usage_notes_hint)
                } else {
                    val latest = notes.first().body.replace('\n', ' ').trim()
                    binding.notesPreview.text = resources.getQuantityString(
                        R.plurals.usage_notes_preview,
                        notes.size,
                        notes.size,
                        latest
                    )
                }
            }
        }
    }
}
