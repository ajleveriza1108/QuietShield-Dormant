package com.ajcoder.quietshield.dormant.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePackageRulesTest {
    @Test
    fun knownAndroidComponentsAreProtected() {
        assertTrue(CorePackageRules.isKnownCore("android"))
        assertTrue(CorePackageRules.isKnownCore("com.android.systemui"))
        assertTrue(CorePackageRules.isKnownCore("com.google.android.gms"))
    }

    @Test
    fun ordinaryAppsAreNotClassifiedAsCoreByName() {
        assertFalse(CorePackageRules.isKnownCore("com.example.musicplayer"))
        assertFalse(CorePackageRules.isKnownCore("com.social.example"))
    }
}
