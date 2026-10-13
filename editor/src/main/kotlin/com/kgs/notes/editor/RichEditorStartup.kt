package com.kgs.notes.editor

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.util.concurrent.Executors

/** Starts the expensive first WebView initialization before the first editor transition. */
object RichEditorStartup {
    private var requested = false

    @Synchronized
    fun prepare(context: Context) {
        if (requested) return
        requested = true
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "kgs-editor-startup").apply { isDaemon = true }
        }
        runCatching {
            WebViewCompat.startUpWebView(
                context.applicationContext,
                WebViewStartUpConfig.Builder(executor).build(),
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        executor.shutdown()
                    }

                    override fun onError(error: WebViewStartupException) {
                        executor.shutdown()
                    }
                },
            )
        }.onFailure { executor.shutdown() }
    }
}
