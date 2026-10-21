package com.posepocket.pose

import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LayerExportStoreTest {
    @get:Rule val dir=TemporaryFolder()
    @Test fun requestSurvivesRecreationUntilCallbackCompletes() {
        val layer="{\"description\":\"Correção de ação\"}"
        LayerExportStore(dir.root).create(layer)
        val recreated=LayerExportStore(dir.root)
        assertEquals(layer,recreated.load()!!.readText())
        recreated.clear()
        assertNull(LayerExportStore(dir.root).load())
    }
}
