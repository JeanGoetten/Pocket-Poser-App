package com.posepocket.privacy

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.posepocket.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CurrentPolicyTest {
    @Test fun executableAndLocalizedDocumentsIdentifyTheSameCurrentPolicy() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val policy=CurrentPolicy.create(context)
        assertTrue(policy.document.contains(CurrentPolicy.VERSION))
        for(language in listOf("en","pt")) {
            val configuration=Configuration(context.resources.configuration).apply { setLocale(Locale(language)) }
            val localized=context.createConfigurationContext(configuration)
            val display=localized.resources.openRawResource(R.raw.privacy_policy_display).bufferedReader().use { it.readText() }
            assertTrue(display.contains(CurrentPolicy.VERSION))
            assertEquals(policy.hash,CurrentPolicy.create(localized).hash)
        }
    }
}
