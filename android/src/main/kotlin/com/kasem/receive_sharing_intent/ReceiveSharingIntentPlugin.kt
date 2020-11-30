package com.kasem.receive_sharing_intent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.plugin.common.PluginRegistry.NewIntentListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection

private const val MESSAGES_CHANNEL = "receive_sharing_intent/messages"
private const val EVENTS_CHANNEL_MEDIA = "receive_sharing_intent/events-media"
private const val EVENTS_CHANNEL_TEXT = "receive_sharing_intent/events-text"
private const val EVENTS_CHANNEL_LINK = "receive_sharing_intent/events-link"

class ReceiveSharingIntentPlugin(val registrar: Registrar) : MethodCallHandler,
        EventChannel.StreamHandler, NewIntentListener {

    private var initialMedia: JSONArray? = null
    private var latestMedia: JSONArray? = null

    private var initialText: String? = null
    private var latestText: String? = null

    private var initialLink: String? = null
    private var latestLink: String? = null

    private var eventSinkMedia: EventChannel.EventSink? = null
    private var eventSinkText: EventChannel.EventSink? = null
    private var eventSinkLink: EventChannel.EventSink? = null

    init {
        handleIntent(registrar.context(), registrar.activity().intent, true)
    }

    override fun onNewIntent(intent: Intent): Boolean {
        handleIntent(registrar.context(), intent, false)
        return false
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        when (arguments) {
            "media" -> eventSinkMedia = events
            "text" -> eventSinkText = events
            "link" -> eventSinkLink = events
        }
    }

    override fun onCancel(arguments: Any?) {
        when (arguments) {
            "media" -> eventSinkMedia = null
            "text" -> eventSinkText = null
            "link" -> eventSinkLink = null
        }
    }

    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
    // plugin registration via this function while apps migrate to use the new Android APIs
    // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
    //
    // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
    // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
    // depending on the user's project. onAttachedToEngine or registerWith must both be defined
    // in the same class.
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            // Detect if we've been launched in background
            if (registrar.activity() == null) {
                return
            }
            val instance = ReceiveSharingIntentPlugin(registrar)

            val mChannel = MethodChannel(registrar.messenger(), MESSAGES_CHANNEL)
            mChannel.setMethodCallHandler(instance)

            val eChannelMedia = EventChannel(registrar.messenger(), EVENTS_CHANNEL_MEDIA)
            eChannelMedia.setStreamHandler(instance)

            val eChannelText = EventChannel(registrar.messenger(), EVENTS_CHANNEL_TEXT)
            eChannelText.setStreamHandler(instance)

            val eChannelLink = EventChannel(registrar.messenger(), EVENTS_CHANNEL_LINK)
            eChannelLink.setStreamHandler(instance)

            registrar.addNewIntentListener(instance)
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getInitialMedia" -> result.success(initialMedia?.toString())
            "getInitialText" -> result.success(initialText)
            "getInitialLink" -> result.success(initialLink)
            "reset" -> {
                initialMedia = null
                latestMedia = null
                initialText = null
                latestText = null
                initialLink = null
                latestLink = null
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun handleIntent(context: Context, intent: Intent, initial: Boolean) {
        when {
            (intent.type?.startsWith("text") != true)
                    && (intent.action == Intent.ACTION_SEND
                    || intent.action == Intent.ACTION_SEND_MULTIPLE) -> { // Sharing images or videos

                val value = getMediaUris(context, intent)
                if (initial) initialMedia = value
                latestMedia = value
                eventSinkMedia?.success(latestMedia?.toString())
            }
            (intent.type == null || intent.type?.startsWith("text") == true)
                    && intent.action == Intent.ACTION_SEND -> { // Sharing text
                val value = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (initial) initialText = value
                latestText = value
                eventSinkText?.success(latestText)
            }
            intent.action == Intent.ACTION_VIEW -> { // Opening URL
                val value = intent.dataString
                if (initial) initialText = value
                latestText = value
                eventSinkText?.success(latestText)
            }
        }
    }

    private fun getMediaUris(context: Context, intent: Intent?): JSONArray? {
        if (intent == null) return null

        return when {
            intent.action == Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return  null
                val path = FileDirectory.getAbsolutePath(context, uri)
                if (path != null) {
                    val type = getMediaType(path)
                    val thumbnail = getThumbnail(context, path, type)
                    val duration = getDuration(path, type)
                    JSONArray().put(
                            JSONObject()
                                    .put("path", path)
                                    .put("type", type.ordinal)
                                    .put("thumbnail", thumbnail)
                                    .put("duration", duration)
                    )
                } else null
            }
            intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                val value = uris?.mapNotNull { uri ->
                    val path = FileDirectory.getAbsolutePath(context, uri) ?: return@mapNotNull null
                    val type = getMediaType(path)
                    val thumbnail = getThumbnail(context, path, type)
                    val duration = getDuration(path, type)
                    return@mapNotNull JSONObject()
                            .put("path", path)
                            .put("type", type.ordinal)
                            .put("thumbnail", thumbnail)
                            .put("duration", duration)
                }?.toList()
                if (value != null) JSONArray(value) else null
            }
            else -> null
        }
    }

    private fun getMediaType(path: String?): MediaType {
        val mimeType = URLConnection.guessContentTypeFromName(path)
        return when {
            mimeType?.startsWith("image") == true -> MediaType.IMAGE
            mimeType?.startsWith("video") == true -> MediaType.VIDEO
            else -> MediaType.FILE
        }
    }

    private fun getThumbnail(context: Context, path: String, type: MediaType): String? {
        if (type != MediaType.VIDEO) return null // get video thumbnail only

        val videoFile = File(path)
        val targetFile = File(context.cacheDir, "${videoFile.name}.png")
        val bitmap = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
                ?: return null
        FileOutputStream(targetFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return targetFile.path
    }

    private fun getDuration(path: String?, type: MediaType?): Long? {
        if (type != MediaType.VIDEO) return null // get duration for video only
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(path)
        val duration = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        retriever.release()
        return duration
    }

    enum class MediaType {
        IMAGE, VIDEO, FILE;
    }
}
