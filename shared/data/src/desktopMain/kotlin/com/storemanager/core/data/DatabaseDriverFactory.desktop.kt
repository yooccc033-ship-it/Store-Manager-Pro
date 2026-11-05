package com.storemanager.core.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

actual class DatabaseDriverFactory(
    private val dbFile: File? = null
) {
    actual fun createDriver(): SqlDriver {
        val targetFile = dbFile ?: getDefaultDatabaseFile()
        val isNewDatabase = !targetFile.exists()

        if (isNewDatabase) {
            targetFile.parentFile?.mkdirs()
        }

        val properties = Properties().apply {
            put("foreign_keys", "true")
            put("busy_timeout", "10000") // 10 seconds busy timeout
            put("journal_mode", "WAL")   // Write-Ahead Logging for high concurrency & safe crash-durability
            put("synchronous", "NORMAL") // Safe crash durability with WAL
        }

        val url = "jdbc:sqlite:${targetFile.absolutePath}"
        val driver = JdbcSqliteDriver(url, properties)

        if (isNewDatabase) {
            // 1. Create 55 Tables and 107 explicit unique/functional indexes from SQLDelight schema
            StoreDatabase.Schema.create(driver)

            // 2. Execute 186 canonical triggers
            initializeCanonicalTriggers(driver)

            // 3. Mark database baseline version
            driver.execute(null, "PRAGMA user_version = 1;", 0)
        } else {
            // Reopening existing database - ensure foreign keys are active
            driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        }

        return driver
    }

    private fun initializeCanonicalTriggers(driver: SqlDriver) {
        val stream = this::class.java.classLoader.getResourceAsStream("canonical_triggers.sql")
            ?: error("canonical_triggers.sql resource not found in classpath")

        val sql = stream.bufferedReader().use { it.readText() }
        executeTriggerScript(driver, sql)
    }

    private fun executeTriggerScript(driver: SqlDriver, script: String) {
        val parts = script.split("CREATE TRIGGER")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotBlank()) {
                val statement = "CREATE TRIGGER " + trimmed
                driver.execute(null, statement, 0)
            }
        }
    }


    companion object {
        fun getDefaultDatabaseFile(): File {
            val os = System.getProperty("os.name").lowercase()
            val baseDir = when {
                os.contains("win") -> {
                    val appData = System.getenv("LOCALAPPDATA")
                        ?: (System.getProperty("user.home") + "\\AppData\\Local")
                    File(appData, "StoreManagerPro\\data")
                }
                os.contains("mac") -> {
                    File(System.getProperty("user.home"), "Library/Application Support/StoreManagerPro/data")
                }
                else -> { // Linux / Unix
                    val xdgData = System.getenv("XDG_DATA_HOME")
                    if (!xdgData.isNullOrBlank()) {
                        File(xdgData, "storemanagerpro/data")
                    } else {
                        File(System.getProperty("user.home"), ".local/share/storemanagerpro/data")
                    }
                }
            }
            return File(baseDir, "store_manager_master.db")
        }

        fun createInMemoryDriver(): SqlDriver {
            val properties = Properties().apply {
                put("foreign_keys", "true")
                put("busy_timeout", "10000")
            }
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, properties)
            StoreDatabase.Schema.create(driver)

            val factory = DatabaseDriverFactory()
            factory.initializeCanonicalTriggers(driver)
            return driver
        }
    }
}
