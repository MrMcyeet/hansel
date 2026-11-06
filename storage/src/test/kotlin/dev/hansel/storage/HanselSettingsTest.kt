package dev.hansel.storage

import dev.hansel.model.EngineId
import dev.hansel.model.EtaProfile
import dev.hansel.model.HighwayPreference
import dev.hansel.model.RoutingOptions
import dev.hansel.routing.ComparisonMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HanselSettingsTest {
    @Test
    fun `default settings produce default routing options`() {
        assertEquals(RoutingOptions(), HanselSettings().routingOptions())
    }

    @Test
    fun `routing options reflect every avoid and preference setting`() {
        val s = HanselSettings(
            avoidTolls = true, avoidFerries = true, avoidUnpaved = true,
            highways = HighwayPreference.PREFER, alternatives = 3, etaProfile = EtaProfile.OBSERVED_BEHAVIOR,
        )
        val o = s.routingOptions()
        assertEquals(true, o.avoidTolls)
        assertEquals(true, o.avoidFerries)
        assertEquals(true, o.avoidUnpaved)
        assertEquals(HighwayPreference.PREFER, o.highways)
        assertEquals(3, o.alternatives)
        assertEquals(EtaProfile.OBSERVED_BEHAVIOR, o.etaProfile)
        assertEquals("en-US", o.language)
    }

    @Test
    fun `negative alternatives are clamped to zero`() {
        assertEquals(0, HanselSettings(alternatives = -1).routingOptions().alternatives)
    }

    @Test
    fun `demo mode routes to the fake engine with no shadow`() {
        val s = HanselSettings(demoMode = true)
        assertEquals(EngineId.FAKE, s.effectivePrimaryEngine)
        assertNull(s.effectiveShadowEngine)
    }

    @Test fun engineSelectionIsOnDeviceUnlessDemo() {
        assertEquals(EngineId.GRAPHHOPPER, HanselSettings().effectivePrimaryEngine)
        assertEquals(EngineId.FAKE, HanselSettings(demoMode = true).effectivePrimaryEngine)
        assertNull(HanselSettings(comparisonMode = ComparisonMode.SHADOW, shadowEngine = EngineId.VALHALLA).effectiveShadowEngine)
    }
}
