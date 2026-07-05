package app.touchgrass

import android.content.Intent
import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(BackgroundServicePlugin::class.java)
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "touchgrass") {
            val screen = uri.host ?: return
            bridge?.webView?.post {
                bridge?.eval("window.dispatchEvent(new CustomEvent('touchgrass:openScreen', { detail: { screen: '$screen' } }))", null)
            }
        }
    }
}
