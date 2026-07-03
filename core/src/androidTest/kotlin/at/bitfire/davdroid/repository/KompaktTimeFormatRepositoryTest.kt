/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Instrumented test for [KompaktTimeFormatRepository]. Exercises the real ContentObserver on
 * Settings.System.TIME_12_24. The setting is changed via a shell command (the shell holds
 * WRITE_SETTINGS), so the observer fires exactly as it does in production.
 *
 * Note: time_12_24 = "24" means 24-hour format, i.e. is24HourFormat = true; "12" => false.
 */
@HiltAndroidTest
class KompaktTimeFormatRepositoryTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var repository: KompaktTimeFormatRepository

    /** Original TIME_12_24 value, restored after each test ("null" = unset). */
    private var original: String? = null

    @Before
    fun setUp() {
        hiltRule.inject()
        original = shell("settings get system time_12_24").trim().let { if (it == "null") null else it }
    }

    @After
    fun tearDown() {
        if (original == null)
            shell("settings delete system time_12_24")
        else
            shell("settings put system time_12_24 $original")
    }

    @Test
    fun is24HourFormat_emitsCurrentValue() = runBlocking {
        setFormat("24")
        assertEquals(true, repository.is24HourFormat.first())
        setFormat("12")
        assertEquals(false, repository.is24HourFormat.first())
    }

    @Test
    fun is24HourFormat_reEmitsWhenFormatChanges() = runBlocking {
        setFormat("24")
        val values = mutableListOf<Boolean>()
        val collector = launch(Dispatchers.IO) {
            repository.is24HourFormat.take(2).toList(values)
        }
        withTimeout(5_000) { while (values.isEmpty()) delay(50) }    // wait for the initial value
        setFormat("12")                                             // flip -> ContentObserver fires
        withTimeout(5_000) { collector.join() }                    // wait for the re-emission
        assertEquals(listOf(true, false), values)
    }


    private fun setFormat(value: String) {
        shell("settings put system time_12_24 $value")
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

}
