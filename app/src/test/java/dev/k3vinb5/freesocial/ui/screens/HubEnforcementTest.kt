package dev.k3vinb5.freesocial.ui.screens

import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.settings.AppMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §2.4 / acceptance item 7 plus the budget-honest rule: the hub pill may only claim filtering
 * when short video is actually gated — the sleep window is active or the daily budget is spent.
 * While budget remains the pill says it is paused and how much time is left. Pure, no Android.
 */
class HubEnforcementTest {

    private val limitMinutes = 20
    private val limitSeconds = limitMinutes * 60L

    private fun status(
        master: Boolean = true,
        service: Boolean = true,
        locked: Boolean = false,
        used: Long = 0L,
    ): HubStatus = hubStatus(
        masterEnabled = master,
        serviceEnabled = service,
        locked = locked,
        usedSeconds = used,
        limitMinutes = limitMinutes,
    )

    // --- truth table ---------------------------------------------------------------------

    @Test
    fun `budget remaining and no lock is paused - nothing is being filtered yet`() {
        assertEquals(HubStatus.PAUSED_BUDGET, status(used = 0L))
        assertEquals(HubStatus.PAUSED_BUDGET, status(used = 400L))
    }

    @Test
    fun `exactly at the limit is exhausted - one second under still has budget`() {
        assertEquals(HubStatus.PAUSED_BUDGET, status(used = limitSeconds - 1))
        assertEquals(HubStatus.FILTERING, status(used = limitSeconds))
        assertEquals(HubStatus.FILTERING, status(used = limitSeconds + 1))
        assertFalse(budgetSpent(limitSeconds - 1, limitMinutes))
        assertTrue(budgetSpent(limitSeconds, limitMinutes))
    }

    @Test
    fun `the sleep window filters even with the whole budget left`() {
        assertEquals(HubStatus.FILTERING, status(locked = true, used = 0L))
        assertEquals(HubStatus.FILTERING, status(locked = true, used = 400L))
    }

    @Test
    fun `a zero limit counts as spent immediately`() {
        assertTrue(budgetSpent(0L, 0))
        assertEquals(HubStatus.FILTERING, hubStatus(true, true, false, 0L, 0))
    }

    @Test
    fun `service off is its own state and outranks lock and budget`() {
        assertEquals(HubStatus.PAUSED_SERVICE, status(service = false))
        assertEquals(HubStatus.PAUSED_SERVICE, status(service = false, used = 400L))
        assertEquals(HubStatus.PAUSED_SERVICE, status(service = false, locked = true, used = limitSeconds))
    }

    @Test
    fun `master off wins over everything`() {
        assertEquals(HubStatus.OFF, status(master = false))
        assertEquals(HubStatus.OFF, status(master = false, service = false, locked = true, used = limitSeconds))
    }

    // --- banner decision -----------------------------------------------------------------

    @Test
    fun `banner decision covers service off and a dead engine`() {
        // Master off: the user chose this, no banner.
        assertEquals(HubBanner.NONE, hubBanner(masterEnabled = false, serviceEnabled = false, engineStale = true))
        assertEquals(HubBanner.NONE, hubBanner(masterEnabled = false, serviceEnabled = true, engineStale = false))

        // A disabled service wins over a dead engine: same Permissions fix, more specific cause.
        assertEquals(HubBanner.SERVICE_OFF, hubBanner(masterEnabled = true, serviceEnabled = false, engineStale = true))

        // D7: master on, service enabled, no heartbeat for 2 min → engine banner.
        assertEquals(HubBanner.ENGINE_STALE, hubBanner(masterEnabled = true, serviceEnabled = true, engineStale = true))

        // Healthy: no banner.
        assertEquals(HubBanner.NONE, hubBanner(masterEnabled = true, serviceEnabled = true, engineStale = false))
    }

    // --- copy ----------------------------------------------------------------------------

    /** The shipped copy, read from the same file the build uses (unit tests run in `app/`). */
    private val strings: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText()
        val entry = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        entry.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `every state has its own pill resource - no two states share copy`() {
        val pills = HubStatus.entries.map(::hubPillRes)
        assertEquals("one distinct pill per state", HubStatus.entries.size, pills.toSet().size)
        assertEquals(R.string.hub_status_on, hubPillRes(HubStatus.FILTERING))
        assertEquals(R.string.hub_status_paused_budget, hubPillRes(HubStatus.PAUSED_BUDGET))
        assertEquals(R.string.hub_status_paused_service, hubPillRes(HubStatus.PAUSED_SERVICE))
        assertEquals(R.string.hub_status_off, hubPillRes(HubStatus.OFF))
    }

    @Test
    fun `the paused pill carries minutes and never claims filtering on`() {
        val pill = strings.getValue("hub_status_paused_budget")
        assertTrue("pill should read 'Paused · N min left': $pill", pill.startsWith("Paused · "))
        assertTrue("pill needs the remaining minutes: $pill", pill.contains("%1\$d"))
        assertNotEquals(strings.getValue("hub_status_on"), pill)
        assertFalse("the paused pill must not claim filtering: $pill", pill.contains("Filtering on"))
    }

    @Test
    fun `the service-off reason never reuses the word paused`() {
        val service = strings.getValue("hub_status_paused_service")
        assertFalse("'paused' now means budget-left; service off is a different reason: $service",
            service.contains("paused", ignoreCase = true))
        assertNotEquals(strings.getValue("hub_status_paused_budget"), service)
        assertNotEquals(strings.getValue("hub_status_on"), service)
    }

    @Test
    fun `only the paused-budget state has a helper line`() {
        assertEquals(R.string.hub_helper_paused_budget, hubHelperRes(HubStatus.PAUSED_BUDGET))
        HubStatus.entries.filter { it != HubStatus.PAUSED_BUDGET }
            .forEach { assertNull("$it must not show a budget helper", hubHelperRes(it)) }

        val helper = strings.getValue("hub_helper_paused_budget")
        assertTrue("helper should explain the budget: $helper", helper.contains("budget"))
        assertFalse("helper must not claim active filtering: $helper", helper.contains("Filtering on"))
    }

    @Test
    fun `obsolete paused copy is gone from resources`() {
        assertFalse("hub_status_paused allowed 'paused' to mean two things", strings.containsKey("hub_status_paused"))
        assertFalse("chip_filter_paused is replaced by the per-reason chips", strings.containsKey("chip_filter_paused"))
    }

    @Test
    fun `the budget screen line is its own copy for the paused state`() {
        val budget = strings.getValue("budget_status_paused_budget")
        assertTrue("budget line needs the remaining minutes: $budget", budget.contains("%1\$d"))
        assertNotEquals(strings.getValue("hub_status_paused_budget"), budget)
        assertFalse(budget.contains("Filtering on"))
    }

    // --- per-app rows --------------------------------------------------------------------

    @Test
    fun `a filter-mode row stops claiming Filtering while budget remains`() {
        assertEquals(AppRowChip.BUDGET_PAUSED, appRowChip(AppMode.FILTER, HubStatus.PAUSED_BUDGET))
        assertEquals(AppRowChip.MODE, appRowChip(AppMode.FILTER, HubStatus.FILTERING))
        assertEquals(AppRowChip.SERVICE_OFF, appRowChip(AppMode.FILTER, HubStatus.PAUSED_SERVICE))
        assertEquals(AppRowChip.OFF, appRowChip(AppMode.FILTER, HubStatus.OFF))
    }

    @Test
    fun `block and allow rows always keep their mode chip`() {
        assertEquals(AppRowChip.MODE, appRowChip(AppMode.BLOCK, HubStatus.PAUSED_BUDGET))
        assertEquals(AppRowChip.MODE, appRowChip(AppMode.ALLOW, HubStatus.PAUSED_BUDGET))
        assertEquals(AppRowChip.MODE, appRowChip(AppMode.BLOCK, HubStatus.FILTERING))
        assertEquals(AppRowChip.MODE, appRowChip(AppMode.ALLOW, HubStatus.FILTERING))
    }
}
