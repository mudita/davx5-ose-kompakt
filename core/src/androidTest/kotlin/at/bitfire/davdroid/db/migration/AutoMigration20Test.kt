/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.db.migration

import at.bitfire.davdroid.db.Service
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@HiltAndroidTest
class AutoMigration20Test: DatabaseMigrationTest(toVersion = 20) {

    @Test
    fun testMigrate_AddsOutcomeTableAndKeepsExistingRows() = testMigration(
        prepare = { db ->
            db.execSQL(
                "INSERT INTO service (id, accountName, type) VALUES (?, ?, ?)",
                arrayOf<Any?>(1, "test", Service.TYPE_CALDAV)
            )
        },
        validate = { db ->
            db.query("SELECT accountName FROM service WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToNext())
                assertEquals("test", cursor.getString(0))
            }

            db.execSQL(
                "INSERT INTO kompakt_sync_outcome (serviceId, dataType, at, succeeded, cause, trigger, detail) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(1, "EVENTS", 1000L, 0, "ServerProblem", "MANUAL", null)
            )
            db.query("SELECT cause, trigger FROM kompakt_sync_outcome WHERE serviceId = 1 AND dataType = 'EVENTS'").use { cursor ->
                assertTrue(cursor.moveToNext())
                assertEquals("ServerProblem", cursor.getString(0))
                assertEquals("MANUAL", cursor.getString(1))
            }
        }
    )

}
