package com.douyindownloader.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DebugSnapshotStore {
    fun saveApiResponse(
        context: Context,
        awemeId: String,
        requestUrl: String,
        responseBody: String,
    ) {
        runCatching {
            val file = File(context.filesDir, "api_response_$awemeId.json")
            JSONObject().apply {
                put("requestUrl", requestUrl)
                put("responseBody", responseBody)
            }.also { payload ->
                file.writeText(payload.toString(2))
            }
        }
    }

    fun saveRequests(
        context: Context,
        awemeId: String,
        pageUrl: String?,
        requests: List<String>,
    ) {
        runCatching {
            val file = File(context.filesDir, "rendered_requests_$awemeId.json")
            JSONObject().apply {
                put("pageUrl", pageUrl ?: JSONObject.NULL)
                put("requests", JSONArray().apply { requests.forEach(::put) })
            }.also { payload ->
                file.writeText(payload.toString(2))
            }
        }
    }

    fun saveSnapshot(
        context: Context,
        awemeId: String,
        snapshot: RenderedPageSnapshot,
        detail: AwemeDetail?,
    ) {
        runCatching {
            val file = File(context.filesDir, "rendered_snapshot_$awemeId.json")
            file.writeText(buildPayload(snapshot, detail).toString(2))
        }
    }

    private fun buildPayload(snapshot: RenderedPageSnapshot, detail: AwemeDetail?): JSONObject {
        return JSONObject().apply {
            put("title", snapshot.title)
            put("scripts", JSONArray().apply { snapshot.scripts.forEach(::put) })
            put(
                "images",
                JSONArray().apply {
                    snapshot.images.forEach { image ->
                        put(
                            JSONObject().apply {
                                put("src", image.src)
                                put("width", image.width)
                                put("height", image.height)
                                put("className", image.className)
                                put("alt", image.alt)
                            },
                        )
                    }
                },
            )
            put("videos", JSONArray().apply { snapshot.videos.forEach(::put) })
            put("requests", JSONArray().apply { snapshot.requests.forEach(::put) })
            put(
                "detail",
                detail?.let { awemeDetail ->
                    JSONObject().apply {
                        put("awemeId", awemeDetail.awemeId)
                        put("description", awemeDetail.description)
                        put("hasImageContent", awemeDetail.hasImageContent)
                        put(
                            "imageAssets",
                            JSONArray().apply {
                                awemeDetail.imageAssets.forEach { asset ->
                                    put(
                                        JSONObject().apply {
                                            put("imageCandidates", JSONArray().apply { asset.imageCandidates.forEach(::put) })
                                            put("motionCandidates", JSONArray().apply { asset.motionCandidates.forEach(::put) })
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "videoCandidates",
                            JSONArray().apply {
                                awemeDetail.videoCandidates.forEach { candidate ->
                                    put(
                                        JSONObject().apply {
                                            put("url", candidate.url)
                                            put("sourcePriority", candidate.sourcePriority)
                                            put("bitrate", candidate.bitrate)
                                            put("height", candidate.height)
                                            put("width", candidate.width)
                                        },
                                    )
                                }
                            },
                        )
                    }
                } ?: JSONObject.NULL,
            )
        }
    }
}
