package com.example.feature.quickcall

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.example.core.FakeSharedPreferences
import com.example.core.LogKeeper
import com.example.feature.element.ElementActionDispatcher
import com.example.feature.element.ElementIdParser
import com.example.feature.floating.trigger.FloatingTriggerCompat
import com.example.feature.floating.trigger.FloatingTriggerTargetValidation
import com.example.feature.sidebar.SidebarItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QuickCallTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class MockTestContext(
        private val prefsByName: MutableMap<String, FakeSharedPreferences>,
        private val baseFilesDir: File
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val key = name ?: "default"
            return prefsByName.getOrPut(key) { FakeSharedPreferences() }
        }
        override fun getFilesDir(): File = baseFilesDir
    }

    private lateinit var prefsMap: MutableMap<String, FakeSharedPreferences>
    private lateinit var testFilesDir: File
    private lateinit var context: MockTestContext

    @Before
    fun setUp() {
        prefsMap = LinkedHashMap()
        testFilesDir = tempFolder.newFolder("quick_call_test")
        context = MockTestContext(prefsMap, testFilesDir)
        QuickCallStore.resetForTesting()
        QuickCallActionHandler.resetHooksForTesting()
    }

    @After
    fun tearDown() {
        QuickCallStore.resetForTesting()
        QuickCallActionHandler.resetHooksForTesting()
    }

    @Test
    fun testElementIdIsUuidBasedAndNeverContainsPhoneNumber() {
        val store = QuickCallStore.getInstance(context)
        val secretNumber = "+1 (555) 867-5309"
        val record = store.createFromContact(
            displayName = "Alice",
            phoneNumber = secretNumber,
            contactLookupKey = "lookup_alice"
        )
        assertNotNull(record)
        assertTrue("Element ID must use quick_call: prefix", record!!.elementId.startsWith("quick_call:"))
        assertFalse("Element ID MUST NOT contain raw phone number", record.elementId.contains("867"))
        assertFalse("Element ID MUST NOT contain phone digits", record.elementId.contains("5309"))
        assertFalse("Element ID MUST NOT contain full number", record.elementId.contains(secretNumber))

        val parsed = ElementIdParser.parse(context, record.elementId)
        assertTrue("ElementIdParser must parse quick_call:<uuid> into SidebarItem.QuickCall", parsed is SidebarItem.QuickCall)
        val qcItem = parsed as SidebarItem.QuickCall
        assertEquals(record.uuid, qcItem.uuid)
        assertEquals("Alice", qcItem.label)
        assertEquals(record.elementId, qcItem.id)

        // Malformed IDs must be rejected
        assertNull(ElementIdParser.parse(context, "quick_call:"))
        assertNull(ElementIdParser.parse(context, "quick_call:   "))
        assertNull(ElementIdParser.parse(context, "quick_call:abc:extra"))
    }

    @Test
    fun testPersistenceSurvivesRestartAndContactDeletion() {
        val store = QuickCallStore.getInstance(context)
        val created = store.createFromContact(
            displayName = "Bob",
            phoneNumber = "+44 20 7946 0958",
            contactLookupKey = "lookup_bob_deleted_later"
        )
        assertNotNull(created)

        // Simulate app/process restart by clearing singleton while preserving SharedPreferences
        QuickCallStore.resetForTesting()

        val reloadedStore = QuickCallStore.getInstance(context)
        val loaded = reloadedStore.getRecord(created!!.uuid)
        assertNotNull("Record must survive restart", loaded)
        assertEquals("Bob", loaded!!.displayName)
        assertEquals("+44 20 7946 0958", loaded.phoneNumber)
        assertEquals("lookup_bob_deleted_later", loaded.contactLookupKey)
        assertFalse(loaded.manuallyEditedNumber)

        // Even with zero Contacts permission / deleted contact, saved snapshot remains executable
        var launchedAction: String? = null
        QuickCallActionHandler.hasCallPhonePermission = { true }
        QuickCallActionHandler.canResolveIntent = { _, _ -> true }
        QuickCallActionHandler.activityLauncher = { _, action, _ ->
            launchedAction = action
            true
        }

        val parsed = ElementIdParser.parse(context, created.elementId) as SidebarItem.QuickCall
        assertTrue(ElementActionDispatcher.execute(context, parsed))
        assertEquals(Intent.ACTION_CALL, launchedAction)
    }

    @Test
    fun testMultipleNumbersPerContactGroupingDeduplicationAndFiltering() {
        val rawRows = listOf(
            RawContactPhoneRow("k_jane", "Jane Doe", "+1 (555) 111-2222", "Mobile"),
            RawContactPhoneRow("k_jane", "Jane Doe", "+1 555-111-2222", "Mobile"), // duplicate normalized
            RawContactPhoneRow("k_jane", "Jane Doe", "555-333-4444", "Work"),
            RawContactPhoneRow("k_empty", "No Valid Number", "not-a-number", "Other"),
            RawContactPhoneRow("k_empty2", "Blank Number", "   ", "Other"),
            RawContactPhoneRow("k_short", "Too Short", "5", "Other"),
            RawContactPhoneRow("k_adam", "Adam Smith", "555-999-0000", "Home")
        )

        val grouped = QuickCallContactsLoader.groupAndFilterRawRows(rawRows)
        assertEquals("Only contacts with valid phone numbers should remain", 2, grouped.size)
        assertEquals("Sorted alphabetically: Adam first", "Adam Smith", grouped[0].displayName)
        assertEquals("Sorted alphabetically: Jane second", "Jane Doe", grouped[1].displayName)
        assertEquals("Jane should have 2 deduplicated numbers", 2, grouped[1].numbers.size)
        assertEquals("+1 (555) 111-2222", grouped[1].numbers[0].number)
        assertEquals("555-333-4444", grouped[1].numbers[1].number)

        // Search by name
        val byName = QuickCallContactsLoader.filterContacts(grouped, "jane")
        assertEquals(1, byName.size)
        assertEquals("Jane Doe", byName[0].displayName)

        // Search by raw digits ignoring formatting
        val byDigits = QuickCallContactsLoader.filterContacts(grouped, "555333")
        assertEquals(1, byDigits.size)
        assertEquals("Jane Doe", byDigits[0].displayName)
    }

    @Test
    fun testInvalidPhoneNumbersAreRejectedCleanly() {
        val store = QuickCallStore.getInstance(context)
        val invalidNumbers = listOf("", "   ", "abc", "---", "1", "call@home", "++123")
        for (bad in invalidNumbers) {
            assertFalse("Expected '$bad' to be invalid", QuickCallNumberValidator.isValidPhoneNumber(bad))
            assertNull("Store must reject invalid number '$bad'", store.createFromContact("Bad", bad))
        }

        // Corrupted record already stored with invalid number must fail safely on execute
        store.saveRecord(QuickCallRecord(uuid = "corrupt_1", displayName = "Broken", phoneNumber = "invalid_text"))
        var launched = false
        val messages = mutableListOf<String>()
        QuickCallActionHandler.hasCallPhonePermission = { true }
        QuickCallActionHandler.canResolveIntent = { _, _ -> true }
        QuickCallActionHandler.activityLauncher = { _, _, _ ->
            launched = true
            true
        }
        QuickCallActionHandler.userMessageNotifier = { _, msg -> messages.add(msg) }

        val outcome = QuickCallActionHandler.executeByUuid(context, "corrupt_1")
        assertEquals(QuickCallDispatchOutcome.INVALID_NUMBER, outcome)
        assertFalse("Invalid number must never launch a call intent", launched)
        assertTrue("User must receive clear feedback on invalid number", messages.isNotEmpty())
    }

    @Test
    fun testEmergencyNumbersRouteToSystemDialerNeverDirectCall() {
        val store = QuickCallStore.getInstance(context)
        val emergencyNumbers = listOf("911", "112", "999")

        for (em in emergencyNumbers) {
            assertTrue("Expected $em to be recognized as emergency", QuickCallNumberValidator.isEmergencyNumber(context, em))
            val rec = store.createFromContact("Emergency $em", em)!!

            var launchedAction: String? = null
            QuickCallActionHandler.hasCallPhonePermission = { true }
            QuickCallActionHandler.canResolveIntent = { _, _ -> true }
            QuickCallActionHandler.activityLauncher = { _, action, _ ->
                launchedAction = action
                true
            }

            val outcome = QuickCallActionHandler.executeByUuid(context, rec.uuid)
            assertEquals("Emergency $em must open system dialer", QuickCallDispatchOutcome.DIALER_OPENED_EMERGENCY, outcome)
            assertEquals("Emergency $em MUST use ACTION_DIAL, never ACTION_CALL", Intent.ACTION_DIAL, launchedAction)
        }
    }

    @Test
    fun testPermissionFlowRequestsCallPhoneAndSupportsDialerFallback() {
        val store = QuickCallStore.getInstance(context)
        val rec = store.createFromContact("Doctor", "+1-555-234-5678")!!

        // Case 1: CALL_PHONE missing -> requests permission via QuickCallPermissionActivity
        var requestedElementId: String? = null
        var directCallLaunched = false
        QuickCallActionHandler.hasCallPhonePermission = { false }
        QuickCallActionHandler.permissionPromptLauncher = { _, elementId ->
            requestedElementId = elementId
            true
        }
        QuickCallActionHandler.activityLauncher = { _, _, _ ->
            directCallLaunched = true
            true
        }

        val outcomeMissing = QuickCallActionHandler.executeByUuid(context, rec.uuid)
        assertEquals(QuickCallDispatchOutcome.PERMISSION_REQUESTED, outcomeMissing)
        assertEquals(rec.elementId, requestedElementId)
        assertFalse("Direct call must not run before CALL_PHONE is granted", directCallLaunched)

        // Case 2: CALL_PHONE denied -> dialer fallback opens ACTION_DIAL
        var fallbackAction: String? = null
        QuickCallActionHandler.canResolveIntent = { _, _ -> true }
        QuickCallActionHandler.activityLauncher = { _, action, _ ->
            fallbackAction = action
            true
        }
        val outcomeFallback = QuickCallActionHandler.launchDialerFallback(context, rec.uuid)
        assertEquals(QuickCallDispatchOutcome.DIALER_FALLBACK_OPENED, outcomeFallback)
        assertEquals(Intent.ACTION_DIAL, fallbackAction)

        // Case 3: CALL_PHONE granted -> launches ACTION_CALL
        var callAction: String? = null
        QuickCallActionHandler.hasCallPhonePermission = { true }
        QuickCallActionHandler.activityLauncher = { _, action, _ ->
            callAction = action
            true
        }
        val outcomeGranted = QuickCallActionHandler.executeByUuid(context, rec.uuid)
        assertEquals(QuickCallDispatchOutcome.CALL_INTENT_LAUNCHED, outcomeGranted)
        assertEquals(Intent.ACTION_CALL, callAction)

        // Case 4: No call or dialer handler installed -> fails gracefully without crashing
        val errorMessages = mutableListOf<String>()
        QuickCallActionHandler.canResolveIntent = { _, _ -> false }
        QuickCallActionHandler.userMessageNotifier = { _, msg -> errorMessages.add(msg) }
        val outcomeNoHandler = QuickCallActionHandler.executeByUuid(context, rec.uuid)
        assertEquals(QuickCallDispatchOutcome.NO_HANDLER, outcomeNoHandler)
        assertTrue(errorMessages.isNotEmpty())
    }

    @Test
    fun testManualEditsRemainAuthoritativeAndCustomIconResetWorks() {
        val store = QuickCallStore.getInstance(context)
        val created = store.createFromContact("Work Desk", "555-0100", "lookup_work")!!
        assertFalse(created.manuallyEditedNumber)

        // Edit label and phone number
        val updated = store.updateRecord(
            uuid = created.uuid,
            displayName = "Work Direct",
            phoneNumber = "+1 555-0199",
            markManuallyEdited = true
        )
        assertNotNull(updated)
        assertEquals("Work Direct", updated!!.displayName)
        assertEquals("+1 555-0199", updated.phoneNumber)
        assertTrue("Manual number edit must set manuallyEditedNumber=true", updated.manuallyEditedNumber)

        // Edit label only later -> manuallyEditedNumber stays true
        val renamedOnly = store.updateRecord(
            uuid = created.uuid,
            displayName = "Office Line",
            phoneNumber = "+1 555-0199",
            markManuallyEdited = true
        )
        assertNotNull(renamedOnly)
        assertTrue("manuallyEditedNumber must remain authoritative", renamedOnly!!.manuallyEditedNumber)

        // Custom icon path save + reset to default
        val fakeIconFile = File(testFilesDir, "custom_icons/quick_call_${created.uuid}.webp").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        assertTrue(store.updateCustomIconPath(created.uuid, fakeIconFile.absolutePath))
        assertEquals(fakeIconFile.absolutePath, store.getRecord(created.uuid)!!.customIconPath)

        assertTrue(store.resetCustomIcon(created.uuid))
        assertNull("Custom icon path must be null after reset", store.getRecord(created.uuid)!!.customIconPath)
    }

    @Test
    fun testZeroPhoneNumberPiiWrittenToLogs() {
        // Enable LogKeeper to prove zero PII is ever logged even when logging is ON
        LogKeeper.setMasterEnabled(true)

        val secretDigits = "7492831044"
        val store = QuickCallStore.getInstance(context)
        val rec = store.createFromContact("Secret Contact", "+1-$secretDigits")!!
        store.updateRecord(rec.uuid, "Updated Contact", "+1-8391047261")

        QuickCallActionHandler.hasCallPhonePermission = { true }
        QuickCallActionHandler.canResolveIntent = { _, _ -> true }
        QuickCallActionHandler.activityLauncher = { _, _, _ -> true }
        QuickCallActionHandler.executeByUuid(context, rec.uuid)

        val logContent = LogKeeper.getDiagnosticLogs(context)
        assertTrue("Expected diagnostic logs to contain QuickCallStore/ActionHandler events", logContent.contains("RECORD_CREATED"))
        assertFalse("Original phone number MUST NOT appear in logs", logContent.contains(secretDigits))
        assertFalse("Updated phone number MUST NOT appear in logs", logContent.contains("8391047261"))
    }

    @Test
    fun testQuickCallEligibilityInFloatingTriggerCompat() {
        val store = QuickCallStore.getInstance(context)
        val valid = store.createFromContact("Home", "+1 555-777-8888")!!

        val validResult = FloatingTriggerCompat.validateTarget(context, valid.elementId)
        assertTrue(
            "Valid Quick Call must be eligible as a standalone Floating Trigger",
            validResult is FloatingTriggerTargetValidation.ValidStandalone
        )

        val missingResult = FloatingTriggerCompat.validateTarget(context, "quick_call:does-not-exist")
        assertTrue(
            "Quick Call missing from store must be rejected as Unsupported",
            missingResult is FloatingTriggerTargetValidation.Unsupported
        )

        val malformedResult = FloatingTriggerCompat.validateTarget(context, "quick_call:")
        assertTrue(
            "Malformed Quick Call ID must be rejected as Unsupported",
            malformedResult is FloatingTriggerTargetValidation.Unsupported
        )
    }
}
