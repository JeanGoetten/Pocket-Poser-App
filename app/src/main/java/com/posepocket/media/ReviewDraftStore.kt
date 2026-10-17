package com.posepocket.media

import android.net.Uri
import android.util.AtomicFile
import com.posepocket.ReviewVideo
import org.json.JSONObject
import java.io.File

/** URI and selected range only; private, never backed up, never uploaded. */
class ReviewDraftStore(root:File) {
    private val file=AtomicFile(File(root,"review-draft.json"))
    fun save(review:ReviewVideo) {
        val value=JSONObject().put("uri",review.uri.toString()).put("source",review.source)
            .put("start",review.startMs).put("end",review.endMs?:JSONObject.NULL)
            .put("focus",review.focus?.let { org.json.JSONArray(listOf(it.left,it.top,it.right,it.bottom)) }?:JSONObject.NULL)
        val out=file.startWrite()
        try{out.write(value.toString().toByteArray());file.finishWrite(out)}catch(error:Exception){file.failWrite(out);throw error}
    }
    fun load():ReviewVideo?=try {
        val value=JSONObject(String(file.readFully(),Charsets.UTF_8))
        val uri=Uri.parse(value.getString("uri"));require(uri.scheme=="content")
        val source=value.getString("source");require(source in listOf("camera","attachment"))
        val start=value.getLong("start");val end=if(value.isNull("end"))null else value.getLong("end")
        require(start>=0&&(end==null||end>start))
        val region=value.optJSONArray("focus")
        val focus=region?.let {
            require(it.length()==4)
            FocusWindow(it.getDouble(0).toFloat(),it.getDouble(1).toFloat(),it.getDouble(2).toFloat(),it.getDouble(3).toFloat())
        }
        ReviewVideo(uri,source,start,end,focus)
    }catch(_:Exception){null}
    fun clear(){file.delete()}
}
