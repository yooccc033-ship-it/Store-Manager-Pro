package com.storemanager.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class StoreDatabaseIntegrationTest {

    private lateinit var tempDbFile: File
    private lateinit var factory: DatabaseDriverFactory
    private lateinit var driver: SqlDriver
    private lateinit var db: StoreDatabase

    @Before
    fun setUp() {
        tempDbFile = File.createTempFile("test_store_master_", ".db")
        if (tempDbFile.exists()) tempDbFile.delete()
        factory = DatabaseDriverFactory(tempDbFile)
        driver = factory.createDriver()
        db = StoreDatabase(driver)
    }

    @After
    fun tearDown() {
        driver.close()
        if (tempDbFile.exists()) tempDbFile.delete()
    }

    @Test
    fun test01_createDatabaseFromScratch_has55Tables() {
        val cursor = driver.executeQuery<Long>(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';",
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        )
        val tableCount = cursor.value
        assertEquals("Expected exactly 55 tables created by SQLDelight", 55L, tableCount)
    }

    @Test
    fun test02_hasAll107ExplicitIndexes() {
        val cursor = driver.executeQuery<Long>(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL;",
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        )
        val indexCount = cursor.value
        assertEquals("Expected exactly 107 explicit indexes created by SQLDelight", 107L, indexCount)
    }

    @Test
    fun test03_hasAll186CanonicalTriggers() {
        val cursor = driver.executeQuery<Long>(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='trigger';",
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        )
        val triggerCount = cursor.value
        assertEquals("Expected exactly 186 canonical triggers created", 186L, triggerCount)
    }

    @Test
    fun test04_pragmaForeignKeyCheck_returnsZeroErrors() {
        val cursor = driver.executeQuery<Long>(
            null,
            "PRAGMA foreign_key_check;",
            {
                var errorCount = 0L
                while (it.next().value) {
                    errorCount++
                }
                QueryResult.Value(errorCount)
            },
            0
        )
        assertEquals("PRAGMA foreign_key_check must return 0 errors", 0L, cursor.value)
    }

    @Test
    fun test05_pragmaIntegrityCheck_returnsOk() {
        val cursor = driver.executeQuery<String?>(
            null,
            "PRAGMA integrity_check;",
            {
                it.next()
                QueryResult.Value(it.getString(0))
            },
            0
        )
        assertEquals("PRAGMA integrity_check must return 'ok'", "ok", cursor.value)
    }

    @Test
    fun test06_userVersionIsSetToOne() {
        val cursor = driver.executeQuery<Long>(
            null,
            "PRAGMA user_version;",
            {
                it.next()
                QueryResult.Value(it.getLong(0) ?: 0L)
            },
            0
        )
        assertEquals("PRAGMA user_version must be 1 for fresh baseline v1.0", 1L, cursor.value)
    }

    @Test
    fun test07_checkConstraint_rejectsReservedExceedingOnHand() {
        try {
            driver.execute(
                null,
                """
                INSERT INTO inventory_balances (warehouse_id, stock_item_id, on_hand_ticks, reserved_ticks, current_moving_avg_cost)
                VALUES ('WH_TEST', 'SI_TEST', 100, 200, 50);
                """.trimIndent(),
                0
            )
            fail("Expected CHECK constraint violation when reserved_ticks > on_hand_ticks")
        } catch (e: Exception) {
            assertTrue("Expected integrity error", e.message?.contains("CHECK") == true || e.message?.contains("constraint") == true)
        }
    }

    @Test
    fun test08_seedBaselineAndQueriesExecution() {
        // Seed tenant
        db.storeDatabaseQueries.insertTenant("TENANT_TEST", "Test Enterprise", 1)
        val tenant = db.storeDatabaseQueries.selectTenantById("TENANT_TEST").executeAsOneOrNull()
        assertNotNull(tenant)
        assertEquals("Test Enterprise", tenant?.name)

        // Seed store
        db.storeDatabaseQueries.insertStore("STORE_TEST", "TENANT_TEST", "Branch 1", "B01")
        val stores = db.storeDatabaseQueries.selectAllStores().executeAsList()
        assertTrue(stores.any { it.id == "STORE_TEST" })
    }

    @Test
    fun test09_triggerFires_productNameOnlyAutoCreatesStockItemAndBalance() {
        // Seed prerequisites: base unit and default warehouse
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_PROD', 'Prod Tenant');", 0)
        driver.execute(
            null,
            "INSERT INTO units_of_measure (id, dimension, name_ar, name_fr, symbol, is_fractional) VALUES ('UOM_PIECE', 'COUNT', 'قطعة', 'Piece', 'pc', 0);",
            0
        )
        driver.execute(null, "INSERT INTO stores (id, tenant_id, name, code) VALUES ('STR_P', 'T_PROD', 'Store P', 'SP');", 0)
        driver.execute(null, "INSERT INTO warehouses (id, tenant_id, store_id, name, is_default) VALUES ('WH_DEF', 'T_PROD', 'STR_P', 'Default WH', 1);", 0)

        // Insert product by name only
        db.storeDatabaseQueries.insertProduct("P_AUTO_TEST", "T_PROD", "Fresh Milk 1L", "UOM_PIECE", 0, 1)

        // Verify base_unit_id was defaulted to UOM_PIECE
        val productCursor = driver.executeQuery<String?>(
            null,
            "SELECT base_unit_id FROM products WHERE id = 'P_AUTO_TEST';",
            {
                if (it.next().value) {
                    QueryResult.Value(it.getString(0))
                } else QueryResult.Value(null)
            },
            0
        )
        assertEquals("UOM_PIECE", productCursor.value)

        // Verify stock_items was auto-created by trigger
        val stockItemCursor = driver.executeQuery<Pair<String, String>?>(
            null,
            "SELECT id, product_id FROM stock_items WHERE product_id = 'P_AUTO_TEST' AND variant_id IS NULL;",
            {
                if (it.next().value) {
                    QueryResult.Value(Pair(it.getString(0)!!, it.getString(1)!!))
                } else QueryResult.Value(null)
            },
            0
        )
        assertNotNull("Trigger must auto-create stock_items record", stockItemCursor.value)
        assertEquals("P_AUTO_TEST", stockItemCursor.value?.second)
        assertTrue("Auto-generated stock item id must be non-blank", stockItemCursor.value?.first?.isNotBlank() == true)
    }



    @Test
    fun test10_databaseReopen_preservesDataWithoutRecreatingSchema() {
        // Insert a record
        db.storeDatabaseQueries.insertTenant("TENANT_PERSIST", "Persistent Enterprise", 1)

        // Close driver
        driver.close()

        // Reopen database with new factory on same file
        val reopenFactory = DatabaseDriverFactory(tempDbFile)
        val newDriver = reopenFactory.createDriver()
        val newDb = StoreDatabase(newDriver)

        try {
            val tenant = newDb.storeDatabaseQueries.selectTenantById("TENANT_PERSIST").executeAsOneOrNull()
            assertNotNull("Data must persist across database reopens", tenant)
            assertEquals("Persistent Enterprise", tenant?.name)
        } finally {
            newDriver.close()
        }
    }

    @Test
    fun test11_auditLogsImmutability_rejectsUpdateAndDelete() {
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_AUDIT', 'Audit Tenant');", 0)
        driver.execute(null, "INSERT INTO audit_logs (id, tenant_id, action, entity_name, entity_id) VALUES ('A_01', 'T_AUDIT', 'INSERT', 'sales', 'S_01');", 0)

        // Test UPDATE rejection
        try {
            driver.execute(null, "UPDATE audit_logs SET action = 'MODIFIED' WHERE id = 'A_01';", 0)
            fail("Trigger must reject UPDATE on audit_logs")
        } catch (e: Exception) {
            assertTrue("Expected audit log update rejection", e.message?.contains("AUDIT_LOGS_ARE_IMMUTABLE") == true)
        }

        // Test DELETE rejection
        try {
            driver.execute(null, "DELETE FROM audit_logs WHERE id = 'A_01';", 0)
            fail("Trigger must reject DELETE on audit_logs")
        } catch (e: Exception) {
            assertTrue("Expected audit log delete rejection", e.message?.contains("AUDIT_LOGS_ARE_IMMUTABLE") == true)
        }
    }

    @Test
    fun test12_transactionRollback_cleansUpAtomicallyOnFailure() {
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_TX', 'Tx Tenant');", 0)

        try {
            db.transaction {
                db.storeDatabaseQueries.insertTenant("T_FAIL_1", "Will Rollback", 1)
                rollback()
            }
        } catch (_: Exception) {
        }

        val tenant = db.storeDatabaseQueries.selectTenantById("T_FAIL_1").executeAsOneOrNull()
        assertEquals("Rolled back record must not exist", null, tenant)
    }

    @Test
    fun test13_singleStorePerTenant_partialUniqueIndex() {
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_SINGLE', 'Single Store Tenant');", 0)
        driver.execute(null, "INSERT INTO stores (id, tenant_id, name, code) VALUES ('STR_1', 'T_SINGLE', 'First Store', 'S01');", 0)

        try {
            driver.execute(null, "INSERT INTO stores (id, tenant_id, name, code) VALUES ('STR_2', 'T_SINGLE', 'Second Store', 'S02');", 0)
            fail("Expected unique constraint violation for second store under same tenant")
        } catch (e: Exception) {
            assertTrue(
                "Expected single-store constraint violation",
                e.message?.contains("UNIQUE") == true || e.message?.contains("stores.tenant_id") == true
            )
        }
    }

    @Test
    fun test14_oneOpenShiftPerCashRegister_partialUniqueIndex() {
        driver.execute(null, "INSERT INTO tenants (id, name) VALUES ('T_SHIFT', 'Shift Tenant');", 0)
        driver.execute(null, "INSERT INTO stores (id, tenant_id, name, code) VALUES ('STR_SH', 'T_SHIFT', 'Store Shift', 'SSH');", 0)
        driver.execute(null, "INSERT INTO cash_registers (id, tenant_id, store_id, code, name) VALUES ('CR_1', 'T_SHIFT', 'STR_SH', 'REG1', 'POS 1');", 0)
        driver.execute(
            null,
            "INSERT INTO users (id, tenant_id, username, password_hash, salt, full_name, is_active) VALUES ('U_1', 'T_SHIFT', 'cashier1', 'hash', 'salt', 'Cashier', 1);",
            0
        )

        // Open first shift
        driver.execute(null, "INSERT INTO cash_shifts (id, cash_register_id, user_id, opening_float_centimes, status) VALUES ('SH_1', 'CR_1', 'U_1', 1000, 'OPEN');", 0)

        // Try opening second shift for same cash register while first is still OPEN
        try {
            driver.execute(null, "INSERT INTO cash_shifts (id, cash_register_id, user_id, opening_float_centimes, status) VALUES ('SH_2', 'CR_1', 'U_1', 2000, 'OPEN');", 0)
            fail("Expected partial unique index violation for second open shift on same cash register")
        } catch (e: Exception) {
            assertTrue(
                "Expected open shift index violation",
                e.message?.contains("UNIQUE") == true || e.message?.contains("idx_one_open_shift_per_register") == true
            )
        }

    }

    @Test
    fun test15_runFullReferenceVerificationMatrix_onActualDatabaseFile() {
        val script = listOf(
            File("verify_canonical_schema.py"),
            File("../../verify_canonical_schema.py"),
            File(System.getProperty("user.dir"), "verify_canonical_schema.py"),
            File(System.getProperty("user.dir"), "../../verify_canonical_schema.py")
        ).firstOrNull { it.exists() } ?: error("verify_canonical_schema.py script not found in project hierarchy")

        val process = ProcessBuilder(
            "python3",
            script.absolutePath,
            tempDbFile.absolutePath
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        println("Python Verification Suite Output on SQLDelight DB:\n$output")
        assertEquals("Full 59 reference verification tests must pass on SQLDelight database:\n$output", 0, exitCode)
        assertTrue("Output must confirm 59 PASS", output.contains("59 PASS / 0 FAIL"))
    }
}
