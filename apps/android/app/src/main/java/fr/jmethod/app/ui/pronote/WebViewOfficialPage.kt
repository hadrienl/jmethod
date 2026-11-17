package fr.jmethod.app.ui.pronote

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import fr.jmethod.app.pronote.OfficialPage

/** Événements de la page officielle transmis au contrôleur, toujours sur le thread principal. */
interface OfficialPageListener {
    fun onPageStarted()
    fun onPageFinished(url: String?)
    fun onBridgeMessage(raw: String?, sourceOrigin: String, isMainFrame: Boolean)
    fun onRendererGone()
}

/**
 * WebView de la page officielle, conservée par le ViewModel pour survivre à une rotation ou à une
 * recréation d’activité. Elle est créée dans un `MutableContextWrapper` rattaché à l’activité
 * courante quand elle est affichée, et à l’application sinon (pas de fuite d’activité).
 */
class WebViewOfficialPage(private val application: Application) : OfficialPage {
    var listener: OfficialPageListener? = null
    private var webView: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var pendingUrl: String? = null
    private val main = Handler(Looper.getMainLooper())

    override val currentUrl: String? get() = webView?.url

    override fun load(url: String) {
        val view = webView
        if (view != null) view.loadUrl(url) else pendingUrl = url
    }

    override fun evaluate(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    override fun release() {
        val view = webView ?: run {
            pendingUrl = null
            return
        }
        webView = null
        pendingUrl = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(view, BRIDGE)
        }
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
        wrapper = null
    }

    override fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    /** Rattache la WebView (créée au besoin) à [activity] et charge l’adresse en attente. */
    fun attach(activity: Context): WebView {
        val view = webView ?: create(activity)
        wrapper?.baseContext = activity
        (view.parent as? ViewGroup)?.removeView(view)
        pendingUrl?.let {
            pendingUrl = null
            view.loadUrl(it)
        }
        return view
    }

    /** Détache la WebView de l’activité qui disparaît, sans la détruire. */
    fun detach(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        if (view === webView) wrapper?.baseContext = application
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(activity: Context): WebView {
        val context = MutableContextWrapper(activity)
        wrapper = context
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // Agent desktop : la page Élèves/Parents desktop expose le bouton du QR d’association.
            settings.userAgentString = DESKTOP_USER_AGENT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(this, BRIDGE, setOf("https://*.index-education.net")) { _, message, sourceOrigin, isMainFrame, _ ->
                    val raw = message.data
                    val origin = sourceOrigin.toString()
                    onMain { listener?.onBridgeMessage(raw, origin, isMainFrame) }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme !in setOf("https", "about")

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    listener?.onPageStarted()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    listener?.onPageFinished(url)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (view === webView) {
                        webView = null
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                        listener?.onRendererGone()
                    }
                    return true
                }
            }
            webView = this
        }
    }

    private companion object {
        const val BRIDGE = "JMethodNative"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
