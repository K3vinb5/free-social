package dev.k3vinb5.freesocial.ui.screens

import dev.k3vinb5.freesocial.settings.PrefsStore
import dev.k3vinb5.freesocial.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The star offer is an offer, not a nag: it sits on the hub until the user dismisses it once, then
 * it is gone permanently — across reloads, across restarts, and with no API that brings it back.
 */
class GithubStarCardTest {

    private class InMemoryPrefs : PrefsStore {
        val booleans = mutableMapOf<String, Boolean>()
        private val ints = mutableMapOf<String, Int>()
        private val longs = mutableMapOf<String, Long>()
        private val strings = mutableMapOf<String, String>()

        override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
        override fun putBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }

        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun getLong(key: String, default: Long) = longs[key] ?: default
        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }

        override fun getString(key: String) = strings[key]
        override fun putString(key: String, value: String) {
            strings[key] = value
        }
    }

    @Test
    fun `card is shown until the user dismisses it`() {
        val settings = SettingsRepository(InMemoryPrefs())

        assertFalse(settings.githubStarCardDismissed)
        assertTrue(showGithubStarCard(settings.githubStarCardDismissed))
    }

    @Test
    fun `dismissing hides the card immediately`() {
        val settings = SettingsRepository(InMemoryPrefs())

        settings.dismissGithubStarCard()

        assertTrue(settings.githubStarCardDismissed)
        assertFalse(showGithubStarCard(settings.githubStarCardDismissed))
    }

    @Test
    fun `dismissal is persisted under its own key`() {
        val store = InMemoryPrefs()

        SettingsRepository(store).dismissGithubStarCard()

        assertEquals(true, store.booleans[SettingsRepository.KEY_GITHUB_STAR_DISMISSED])
    }

    @Test
    fun `dismissal survives a new repository instance`() {
        val store = InMemoryPrefs()
        SettingsRepository(store).dismissGithubStarCard()

        // A fresh instance is what a restart, a new screen or the service would see.
        val restarted = SettingsRepository(store)
        assertTrue(restarted.githubStarCardDismissed)
        assertFalse(showGithubStarCard(restarted.githubStarCardDismissed))
    }

    @Test
    fun `dismissal is never undone by reload or other settings writes`() {
        val store = InMemoryPrefs()
        val settings = SettingsRepository(store)
        settings.dismissGithubStarCard()

        settings.reload()
        settings.updateFiltering(false)
        settings.updateBudgetLimit(45)
        settings.reload()

        assertTrue(settings.githubStarCardDismissed)
        assertTrue(SettingsRepository(store).githubStarCardDismissed)
    }

    @Test
    fun `dismissing twice stays dismissed`() {
        val settings = SettingsRepository(InMemoryPrefs())

        settings.dismissGithubStarCard()
        settings.dismissGithubStarCard()

        assertTrue(settings.githubStarCardDismissed)
    }

    @Test
    fun `star action points at the public repository over https`() {
        assertEquals("https://github.com/K3vinb5/free-social", SettingsRepository.GITHUB_REPO_URL)
        assertTrue(SettingsRepository.GITHUB_REPO_URL.startsWith("https://"))
    }
}
