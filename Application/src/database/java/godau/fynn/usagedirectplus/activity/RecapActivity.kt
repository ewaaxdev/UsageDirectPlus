package godau.fynn.usagedirectplus.activity

import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import godau.fynn.usagedirectplus.R
import godau.fynn.usagedirectplus.databinding.ActivityRecapBinding
import godau.fynn.usagedirectplus.databinding.RowRecapAppBinding
import godau.fynn.usagedirectplus.persistence.EventLogRunnable
import godau.fynn.usagedirectplus.persistence.HistoryDatabase
import godau.fynn.usagedirectplus.persistence.ScreenEvent
import godau.fynn.usagedirectplus.persistence.UsageInterval
import godau.fynn.usagedirectplus.wrapper.UsageStatsManagerWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One consolidated overview of screen time: totals, daily rhythm, top apps,
 * insights and an honest note about where the numbers come from.
 */
class RecapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecapBinding

    private var generation = 0
    private var syncedOnce = false

    private enum class Range { TODAY, WEEK, MONTH, ALL }

    private data class RecapData(
        val range: Range,
        val total: Long,
        val averagePerDay: Long,
        val previousTotal: Long,
        val appCount: Int,
        val activityCount: Int,
        val activityLabel: Int,
        val perDay: List<Pair<Long, Long>>,
        val hourly: List<Long>,
        val topApps: List<Pair<String, Long>>,
        val peakHour: Int?,
        val longestSession: UsageInterval?,
        val longestSessionDuration: Long,
        val sessions: Int,
        val intervalTotal: Long,
        val lastRecord: Long?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.recap_title)

        binding.recapRangeChips.setOnCheckedStateChangeListener { _, _ -> reload() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun selectedRange(): Range {
        return when (binding.recapRangeChips.checkedChipId) {
            R.id.recap_chip_today -> Range.TODAY
            R.id.recap_chip_month -> Range.MONTH
            R.id.recap_chip_all -> Range.ALL
            else -> Range.WEEK
        }
    }

    private fun reload() {
        val currentRange = selectedRange()
        val request = ++generation

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                syncIfNeeded()
                buildRecap(currentRange)
            }

            if (request != generation) return@launch
            render(data)
        }
    }

    private fun syncIfNeeded() {
        if (syncedOnce) return
        syncedOnce = true

        if (!UsageStatsManagerWrapper.isPermissionGranted(this)) return

        try {
            EventLogRunnable(this).run()
        } catch (_: Exception) {
            // A failed sync only means the numbers are as fresh as the last scheduled run.
        }
    }

    private fun buildRecap(range: Range): RecapData {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayEpochDay = today.toEpochDay()

        val database = HistoryDatabase.get(this)
        try {
            val statsDao = database.getUsageStatsDao()
            val intervalDao = database.getUsageIntervalDao()
            val screenEventDao = database.getScreenEventDao()

            val storedDays = statsDao.getDaysStored()
            val earliestDay = storedDays.minOrNull() ?: todayEpochDay

            val startDay = when (range) {
                Range.TODAY -> todayEpochDay
                Range.WEEK -> today.minusDays(6).toEpochDay()
                Range.MONTH -> today.minusDays(29).toEpochDay()
                Range.ALL -> minOf(earliestDay, todayEpochDay)
            }
            val endDay = todayEpochDay

            val startMillis = LocalDate.ofEpochDay(startDay)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = today.plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()

            val total = statsDao.getTotalTimeUsedBetween(startDay, endDay)
            val perDayRows = statsDao.getTotalTimePerDayBetween(startDay, endDay)
            val perAppRows = statsDao.getTotalTimePerAppBetween(startDay, endDay)

            val daysInRange = (endDay - startDay + 1).toInt()
            val perDayMap = LinkedHashMap<Long, Long>()
            for (day in startDay..endDay) {
                perDayMap[day] = 0L
            }
            for (row in perDayRows) {
                perDayMap[row.day] = row.timeUsed
            }

            val previousLength = max(1, daysInRange)
            val previousEnd = startDay - 1
            val previousStart = previousEnd - (previousLength - 1)
            val previousTotal = if (previousEnd >= 0) {
                statsDao.getTotalTimeUsedBetween(previousStart, previousEnd)
            } else {
                0L
            }

            val intervals = intervalDao.getByTimeRange(startMillis, endMillis)
            val hourly = LongArray(24)
            var intervalTotal = 0L
            var sessions = 0
            var longestDuration = 0L
            var longestSession: UsageInterval? = null

            for (interval in intervals) {
                val begin = max(interval.beginTime, startMillis)
                val end = min(interval.endTime, endMillis)
                if (end <= begin) continue

                val duration = end - begin
                intervalTotal += duration
                sessions++

                if (duration > longestDuration) {
                    longestDuration = duration
                    longestSession = interval
                }

                var cursor = begin
                while (cursor < end) {
                    val hourStart = Instant.ofEpochMilli(cursor).atZone(zone)
                    val nextHour = hourStart.truncatedTo(ChronoUnit.HOURS)
                        .plusHours(1).toInstant().toEpochMilli()
                    val segmentEnd = min(end, nextHour)
                    hourly[hourStart.hour] += segmentEnd - cursor
                    cursor = segmentEnd
                }
            }

            val peakHour = if (hourly.any { it > 0 }) {
                hourly.indices.maxByOrNull { hourly[it] }
            } else {
                null
            }

            val activityCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                screenEventDao.getUnlockCount(startMillis, endMillis)
            } else {
                screenEventDao.getCountByType(ScreenEvent.SCREEN_ON, startMillis, endMillis)
            }
            val activityLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                R.string.recap_unlocks
            } else {
                R.string.recap_screen_ons
            }

            val lastRecord = listOfNotNull(
                intervalDao.getLatestEndTime(),
                screenEventDao.getLastScreenEventTimestamp()
            ).maxOrNull()

            return RecapData(
                range = range,
                total = total,
                averagePerDay = total / max(1, daysInRange),
                previousTotal = previousTotal,
                appCount = perAppRows.size,
                activityCount = activityCount,
                activityLabel = activityLabel,
                perDay = perDayMap.toList(),
                hourly = hourly.toList(),
                topApps = perAppRows.take(6).map { it.applicationId to it.timeUsed },
                peakHour = peakHour,
                longestSession = longestSession,
                longestSessionDuration = longestDuration,
                sessions = sessions,
                intervalTotal = intervalTotal,
                lastRecord = lastRecord
            )
        } finally {
            database.close()
        }
    }

    private fun render(data: RecapData) {
        binding.recapTotal.text = formatDuration(data.total)
        binding.recapAverageValue.text = formatDuration(data.averagePerDay)
        binding.recapAppsValue.text = data.appCount.toString()
        binding.recapUnlocksValue.text = data.activityCount.toString()
        binding.recapUnlocksLabel.setText(data.activityLabel)

        if (data.previousTotal > 0L) {
            val delta = ((data.total - data.previousTotal) * 100.0 / data.previousTotal).roundToInt()
            binding.recapCompare.text = when {
                delta > 0 -> getString(R.string.recap_compare_more, abs(delta))
                delta < 0 -> getString(R.string.recap_compare_less, abs(delta))
                else -> getString(R.string.recap_compare_same)
            }
        } else {
            binding.recapCompare.setText(R.string.recap_compare_none)
        }

        val hasData = data.total > 0L || data.sessions > 0
        binding.recapEmpty.isVisible = !hasData
        binding.recapCardChart.isVisible = hasData
        binding.recapCardApps.isVisible = hasData
        binding.recapCardInsights.isVisible = hasData
        binding.recapCardAccuracy.isVisible = hasData

        renderChart(data)
        renderTopApps(data)
        renderInsights(data)
        renderAccuracy(data)
    }

    private fun renderChart(data: RecapData) {
        if (data.range == Range.TODAY) {
            binding.recapChartTitle.setText(R.string.recap_daily_rhythm)
            binding.recapChartSubtitle.setText(R.string.recap_chart_by_hour)

            val labels = data.hourly.indices.map { hour ->
                when (hour) {
                    0 -> "00"
                    6 -> "06"
                    12 -> "12"
                    18 -> "18"
                    23 -> "23"
                    else -> ""
                }
            }
            binding.recapChart.setData(data.hourly, labels)
        } else {
            binding.recapChartTitle.setText(R.string.recap_daily_rhythm)
            binding.recapChartSubtitle.setText(R.string.recap_chart_per_day)

            val values = data.perDay.map { it.second }
            val labelEvery = max(1, (values.size + 5) / 6)
            val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            val labels = data.perDay.mapIndexed { index, pair ->
                val isLast = index == data.perDay.lastIndex
                if (index % labelEvery == 0 || isLast) {
                    LocalDate.ofEpochDay(pair.first).format(formatter)
                } else {
                    ""
                }
            }
            binding.recapChart.setData(values, labels)
        }
    }

    private fun renderTopApps(data: RecapData) {
        binding.recapTopApps.removeAllViews()
        binding.recapTopEmpty.isVisible = data.topApps.isEmpty()

        if (data.topApps.isEmpty()) return

        val maximum = max(1L, data.topApps.first().second)
        val inflater = LayoutInflater.from(this)

        for ((packageName, timeUsed) in data.topApps) {
            val row = RowRecapAppBinding.inflate(inflater, binding.recapTopApps, false)
            row.recapAppName.text = appLabel(packageName)
            row.recapAppTime.text = getString(
                R.string.recap_app_time,
                formatDuration(timeUsed),
                if (data.total > 0) ((timeUsed * 100.0) / data.total).roundToInt() else 0
            )
            row.recapAppBar.max = 100
            row.recapAppBar.progress = ((timeUsed * 100.0) / maximum).roundToInt().coerceIn(0, 100)
            row.recapAppIcon.setImageDrawable(appIcon(packageName))
            binding.recapTopApps.addView(row.root)
        }
    }

    private fun renderInsights(data: RecapData) {
        binding.recapInsights.removeAllViews()

        val insights = mutableListOf<String>()

        data.peakHour?.let { hour ->
            val hourMillis = data.hourly[hour]
            insights.add(
                getString(
                    R.string.recap_insight_peak_hour,
                    String.format(Locale.getDefault(), "%02d:00–%02d:00", hour, (hour + 1) % 24),
                    formatDuration(hourMillis)
                )
            )
        }

        data.longestSession?.let { session ->
            insights.add(
                getString(
                    R.string.recap_insight_longest_session,
                    appLabel(session.applicationId),
                    formatDuration(data.longestSessionDuration)
                )
            )
        }

        val busiestDay = data.perDay.maxByOrNull { it.second }
        if (busiestDay != null && busiestDay.second > 0L) {
            val formatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
            insights.add(
                getString(
                    R.string.recap_insight_busiest_day,
                    LocalDate.ofEpochDay(busiestDay.first).format(formatter),
                    formatDuration(busiestDay.second)
                )
            )
        }

        if (data.sessions > 0) {
            insights.add(
                getString(
                    R.string.recap_insight_sessions,
                    data.sessions,
                    formatDuration(data.intervalTotal / data.sessions)
                )
            )
        }

        if (insights.isEmpty()) {
            insights.add(getString(R.string.recap_no_data))
        }

        for (line in insights) {
            val view = TextView(this)
            view.setTextAppearance(R.style.TextAppearance_Notes_Body)
            view.textSize = 13f
            view.setTextColor(getColor(R.color.glass_text_primary))
            view.setLineSpacing(resources.displayMetrics.density * 2f, 1f)
            view.setPadding(0, 0, 0, (6 * resources.displayMetrics.density).roundToInt())
            view.text = line
            binding.recapInsights.addView(view)
        }
    }

    private fun renderAccuracy(data: RecapData) {
        val lastRecord = data.lastRecord?.let { timestamp ->
            Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()))
        } ?: getString(R.string.recap_accuracy_never)

        val base = getString(
            R.string.recap_accuracy_body,
            data.sessions,
            formatDuration(data.intervalTotal),
            lastRecord
        )

        val difference = abs(data.total - data.intervalTotal)
        val extra = if (difference > 2 * 60 * 1000L) {
            "\n\n" + getString(R.string.recap_accuracy_difference, formatDuration(difference))
        } else {
            ""
        }

        binding.recapAccuracyBody.text = base + extra
    }

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / (60 * 1000L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            getString(R.string.recap_duration_hours, hours, minutes)
        } else {
            getString(R.string.recap_duration_minutes, minutes)
        }
    }

    private fun appLabel(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun appIcon(packageName: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }
}
