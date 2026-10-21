package com.posepocket

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class LocaleResourcesTest {
    private fun localized(tag: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    private fun policy(context: Context, display: Boolean): String {
        val id = if (display) R.raw.privacy_policy_display else R.raw.privacy_policy
        return context.resources.openRawResource(id).bufferedReader().use { it.readText() }
    }

    @Test fun portugueseFromBrazilAndPortugalUseTheSameResources() {
        for (tag in listOf("pt-BR", "pt-PT")) {
            val context = localized(tag)
            assertEquals("Anexar vídeo", context.getString(R.string.attach_video))
            assertEquals("Sua privacidade vem primeiro", context.getString(R.string.privacy_first))
            assertTrue(policy(context, true).contains("Vídeos de origem com mais de 250 MB"))
        }
    }

    @Test fun allOtherLocalesFallBackToEnglish() {
        for (tag in listOf("en-US", "es-ES", "fr-FR")) {
            val context = localized(tag)
            assertEquals("Attach video", context.getString(R.string.attach_video))
            assertEquals("Your privacy comes first", context.getString(R.string.privacy_first))
            assertTrue(policy(context, true).contains("Source videos may be up to 250 MB"))
        }
    }

    @Test fun processingAndNvidiaErrorsFollowSystemLanguage() {
        val pt = localized("pt-BR")
        val es = localized("es-ES")
        assertEquals(
            "Nenhuma pessoa detectada. Use um vídeo com o corpo inteiro visível e bem iluminado.",
            localizeUiMessage(pt, "Nenhuma pessoa detectada. Use um vídeo com o corpo inteiro visível e bem iluminado."),
        )
        assertEquals(
            "No person detected. Use a well-lit video with the full body visible.",
            localizeUiMessage(es, "Nenhuma pessoa detectada. Use um vídeo com o corpo inteiro visível e bem iluminado."),
        )
        assertEquals(
            "Step 2/3 — Normalizing video on device 42%",
            localizeUiMessage(es, "Etapa 2/3 — Normalizando vídeo no celular: 42%"),
        )
    }
    @Test fun consentPolicyIdentityIsStableWhenTheSystemLanguageChanges() {
        val pt = localized("pt-BR")
        val en = localized("en-US")
        assertEquals(policy(pt, false), policy(en, false))
        assertTrue(policy(pt, false).contains(com.posepocket.privacy.CurrentPolicy.VERSION))
        assertTrue(policy(en, true).contains(com.posepocket.privacy.CurrentPolicy.VERSION))
    }
}
