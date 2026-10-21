package com.posepocket.pose

import android.util.AtomicFile
import java.io.File

/** One export request survives activity and process recreation while the document picker is open. */
class LayerExportStore(root:File) {
    private val directory=File(root,"exports").apply { mkdirs() }
    private val file=AtomicFile(File(directory,"pending-layer.json"))
    @Synchronized fun create(layer:String):File {
        val out=file.startWrite()
        try { out.write(layer.toByteArray(Charsets.UTF_8));out.fd.sync();file.finishWrite(out) }
        catch(error:Exception){file.failWrite(out);throw error}
        return requireNotNull(load())
    }
    @Synchronized fun load():File?=try { file.openRead().use { };file.baseFile }catch(_:java.io.FileNotFoundException){null}
    @Synchronized fun clear(){file.delete()}
}
