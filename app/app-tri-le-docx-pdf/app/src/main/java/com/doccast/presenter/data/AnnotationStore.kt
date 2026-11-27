package com.doccast.presenter.data

import android.content.Context
import com.doccast.presenter.model.NormalizedPoint
import com.doccast.presenter.model.NormalizedStroke
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists per-page ink strokes locally per document URI so drawings survive
 * page switches and app restarts.
 */
class AnnotationStore(context: Context) {
    private val prefs = context.getSharedPreferences("doccast_annotations", Context.MODE_PRIVATE)

    private fun keyFor(uriString: String, pageIndex: Int): String =
        "${uriString.hashCode()}_p$pageIndex"

    fun savePageStrokes(uriString: String, pageIndex: Int, strokes: List<NormalizedStroke>) {
        val key = keyFor(uriString, pageIndex)
        if (strokes.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val arr = JSONArray()
        for (stroke in strokes) {
            val obj = JSONObject()
            obj.put("color", stroke.colorHex)
            obj.put("width", stroke.strokeWidthRatio.toDouble())
            val pts = JSONArray()
            for (pt in stroke.points) {
                val pObj = JSONObject()
                pObj.put("x", pt.x.toDouble())
                pObj.put("y", pt.y.toDouble())
                pts.put(pObj)
            }
            obj.put("points", pts)
            arr.put(obj)
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun loadPageStrokes(uriString: String, pageIndex: Int): List<NormalizedStroke> {
        val raw = prefs.getString(keyFor(uriString, pageIndex), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<NormalizedStroke>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val color = obj.optString("color", "#EF4444")
                val width = obj.optDouble("width", 0.006).toFloat()
                val ptsArr = obj.getJSONArray("points")
                val pts = mutableListOf<NormalizedPoint>()
                for (j in 0 until ptsArr.length()) {
                    val pObj = ptsArr.getJSONObject(j)
                    pts.add(
                        NormalizedPoint(
                            pObj.getDouble("x").toFloat(),
                            pObj.getDouble("y").toFloat()
                        )
                    )
                }
                result.add(NormalizedStroke(pts, color, width))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}
