package godau.fynn.usagedirectplus.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import godau.fynn.usagedirectplus.SimpleUsageStat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecapQueriesTest {

    private lateinit var database: HistoryDatabase
    private lateinit var usageStatsDao: UsageStatsDao
    private lateinit var screenEventDao: ScreenEventDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HistoryDatabase::class.java
        ).allowMainThreadQueries().build()
        usageStatsDao = database.getUsageStatsDao()
        screenEventDao = database.getScreenEventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rangeTotals_sumOnlyRequestedDaysAndSkipHiddenEntries() {
        usageStatsDao.insert(
            listOf(
                SimpleUsageStat(day = 10L, timeUsed = 1_000L, applicationId = "com.example.a"),
                SimpleUsageStat(day = 11L, timeUsed = 2_000L, applicationId = "com.example.a"),
                SimpleUsageStat(day = 12L, timeUsed = 4_000L, applicationId = "com.example.b"),
                SimpleUsageStat(day = 20L, timeUsed = 8_000L, applicationId = "com.example.b")
            )
        )
        usageStatsDao.markHidden(
            SimpleUsageStat(day = 12L, timeUsed = 4_000L, applicationId = "com.example.b")
        )

        assertThat(usageStatsDao.getTotalTimeUsedBetween(10L, 12L)).isEqualTo(3_000L)
        assertThat(usageStatsDao.getTotalTimeUsedBetween(10L, 20L)).isEqualTo(11_000L)
        assertThat(usageStatsDao.getTotalTimeUsedBetween(13L, 19L)).isEqualTo(0L)
    }

    @Test
    fun rangeTotals_groupPerDayInAscendingOrder() {
        usageStatsDao.insert(
            listOf(
                SimpleUsageStat(day = 12L, timeUsed = 300L, applicationId = "com.example.a"),
                SimpleUsageStat(day = 10L, timeUsed = 100L, applicationId = "com.example.a"),
                SimpleUsageStat(day = 12L, timeUsed = 200L, applicationId = "com.example.b"),
                SimpleUsageStat(day = 11L, timeUsed = 50L, applicationId = "com.example.b")
            )
        )

        val perDay = usageStatsDao.getTotalTimePerDayBetween(10L, 12L)

        assertThat(perDay.map { it.day }).containsExactly(10L, 11L, 12L).inOrder()
        assertThat(perDay.map { it.timeUsed }).containsExactly(100L, 50L, 500L).inOrder()
    }

    @Test
    fun rangeTotals_groupPerAppDescendingWithResolvedPackageName() {
        usageStatsDao.insert(
            listOf(
                SimpleUsageStat(day = 10L, timeUsed = 900L, applicationId = "com.example.big"),
                SimpleUsageStat(day = 11L, timeUsed = 600L, applicationId = "com.example.big"),
                SimpleUsageStat(day = 11L, timeUsed = 500L, applicationId = "com.example.small"),
                SimpleUsageStat(day = 10L, timeUsed = 100L, applicationId = "com.example.small")
            )
        )

        val perApp = usageStatsDao.getTotalTimePerAppBetween(10L, 11L)

        assertThat(perApp).hasSize(2)
        assertThat(perApp[0].applicationId).isEqualTo("com.example.big")
        assertThat(perApp[0].timeUsed).isEqualTo(1_500L)
        assertThat(perApp[1].applicationId).isEqualTo("com.example.small")
        assertThat(perApp[1].timeUsed).isEqualTo(600L)
    }

    @Test
    fun screenEventQueries_countTypesInsideRangeOnly() {
        screenEventDao.insert(
            listOf(
                ScreenEvent(timestamp = 1_000L, eventType = ScreenEvent.KEYGUARD_HIDDEN),
                ScreenEvent(timestamp = 2_000L, eventType = ScreenEvent.KEYGUARD_HIDDEN),
                ScreenEvent(timestamp = 3_000L, eventType = ScreenEvent.SCREEN_ON),
                ScreenEvent(timestamp = 9_000L, eventType = ScreenEvent.KEYGUARD_HIDDEN)
            )
        )

        assertThat(screenEventDao.getCountByType(ScreenEvent.SCREEN_ON, 0L, 5_000L)).isEqualTo(1)
        assertThat(screenEventDao.getCountByType(ScreenEvent.KEYGUARD_HIDDEN, 0L, 5_000L)).isEqualTo(2)
        assertThat(screenEventDao.getLastScreenEventTimestamp()).isEqualTo(9_000L)
    }

    @Test
    fun intervalsOverlappingRangeAreReturnedForSessionMath() {
        val intervalDao = database.getUsageIntervalDao()
        intervalDao.insertNonOverlapping(
            listOf(
                UsageInterval(beginTime = 1_000L, endTime = 2_000L, applicationId = "com.example.a"),
                UsageInterval(beginTime = 5_000L, endTime = 7_000L, applicationId = "com.example.b")
            )
        )

        val overlapping = intervalDao.getByTimeRange(1_500L, 6_000L)

        assertThat(overlapping).hasSize(2)
        assertThat(intervalDao.getLatestEndTime()).isEqualTo(7_000L)
    }
}
