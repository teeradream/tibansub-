package com.metallic.chiaki.tibansub.xbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hardware-accelerated WebRTC player & clean un-occluded frame grabber for Xbox Series X|S & Xbox One.
 * Mounts directly inside StreamActivity's aspectRatioLayout so 100% of StreamActivity's classic
 * subtitle overlay, left tutorial box, right phone chat bubble, TTS, and 3-line (☰) menu work identically!
 */
@SuppressLint("SetJavaScriptEnabled")
class XboxWebRtcBridge(
    context: Context,
    private val config: XboxSessionLaunchConfig,
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val authManager = XboxAuthManager(context)
    val webView: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.domStorageEnabled = true
        webChromeClient = WebChromeClient()
        webViewClient = WebViewClient()
    }

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var pendingFrameCallback: ((Bitmap?) -> Unit)? = null

    init {
        webView.addJavascriptInterface(JsHostBridge(), "TibanXboxBridge")
    }

    fun start() {
        webView.loadDataWithBaseURL("https://xbox.local/", PLAYER_HTML, "text/html", "UTF-8", null)
    }

    fun setAudioMuted(muted: Boolean) {
        webView.post {
            webView.evaluateJavascript("window.setXboxMuted && window.setXboxMuted($muted);", null)
        }
    }

    fun captureFullFrame1080p(callback: (Bitmap?) -> Unit) {
        if (!isConnected) {
            callback(null)
            return
        }
        pendingFrameCallback = callback
        webView.post {
            webView.evaluateJavascript("window.captureVideoJpeg && window.captureVideoJpeg();", null)
        }
    }

    fun shutdown() {
        isConnected = false
        pendingFrameCallback = null
        try {
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Exception) {}
    }

    inner class JsHostBridge {
        @JavascriptInterface
        fun onLocalOfferReady(sdpOffer: String, iceJson: String) {
            scope.launch(Dispatchers.Main) {
                val res = authManager.negotiateWebRtcSession(config, sdpOffer, iceJson)
                res.onSuccess { (answerSdp, remoteIceJson) ->
                    val b64Sdp = Base64.encodeToString(answerSdp.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val b64Ice = Base64.encodeToString(remoteIceJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    webView.evaluateJavascript("window.applyRemoteAnswer('$b64Sdp', '$b64Ice');", null)
                }.onFailure { err ->
                    onError(err.message ?: "เชื่อมต่อเครื่อง Xbox ไม่สำเร็จ")
                }
            }
        }

        @JavascriptInterface
        fun onStreamPlaying() {
            isConnected = true
            scope.launch(Dispatchers.Main) {
                onConnected()
            }
        }

        @JavascriptInterface
        fun onStreamError(msg: String) {
            scope.launch(Dispatchers.Main) {
                onError(msg)
            }
        }

        @JavascriptInterface
        fun onFrameBase64(b64Jpeg: String) {
            val cb = pendingFrameCallback ?: return
            pendingFrameCallback = null
            if (b64Jpeg.isBlank()) {
                cb(null)
                return
            }
            try {
                val bytes = Base64.decode(b64Jpeg, Base64.DEFAULT)
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = false
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                cb(bmp)
            } catch (_: Exception) {
                cb(null)
            }
        }
    }

    companion object {
        private const val PLAYER_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
<style>
  html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; }
  video { width:100%; height:100%; object-fit:contain; background:#000; }
</style>
</head>
<body>
<video id="xvideo" autoplay playsinline></video>
<canvas id="xcanvas" width="1920" height="1080" style="display:none;"></canvas>
<script>
(function(){
  const video = document.getElementById('xvideo');
  const canvas = document.getElementById('xcanvas');
  const ctx = canvas.getContext('2d', { willReadFrequently: false });
  const pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
  const candidates = [];

  pc.createDataChannel('chat', { ordered: true, protocol: 'chatV1' });
  const ctrl = pc.createDataChannel('control', { ordered: true, protocol: 'controlV1' });
  pc.createDataChannel('input', { ordered: true, protocol: '1.0' });
  const msgCh = pc.createDataChannel('message', { ordered: true, protocol: 'messageV1' });

  ctrl.onopen = () => {
    try {
      ctrl.send(JSON.stringify({ message: 'authorizationRequest', accessKey: '4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E' }));
      ctrl.send(JSON.stringify({ message: 'gamepadChanged', gamepadIndex: 0, wasAdded: false }));
    } catch(e) {}
  };

  pc.addTransceiver('video', { direction: 'recvonly' });
  pc.addTransceiver('audio', { direction: 'recvonly' });

  pc.ontrack = (ev) => {
    if (ev.streams && ev.streams[0]) {
      video.srcObject = ev.streams[0];
      video.play().catch(()=>{});
    }
  };

  video.onplaying = () => {
    TibanXboxBridge.onStreamPlaying();
  };

  pc.onicecandidate = (ev) => {
    if (ev.candidate) {
      candidates.push({ candidate: ev.candidate.candidate, sdpMid: ev.candidate.sdpMid, sdpMLineIndex: ev.candidate.sdpMLineIndex });
    }
  };

  window.applyRemoteAnswer = async function(b64Sdp, b64Ice) {
    try {
      const sdp = atob(b64Sdp);
      await pc.setRemoteDescription({ type: 'answer', sdp: sdp });
      const iceRaw = atob(b64Ice);
      const arr = JSON.parse(iceRaw || '[]');
      for (const item of arr) {
        const cStr = item.candidate || item;
        if (cStr && typeof cStr === 'string' && cStr.indexOf('candidate:') !== -1) {
          await pc.addIceCandidate({ candidate: cStr, sdpMid: item.sdpMid || '0', sdpMLineIndex: item.sdpMLineIndex || 0 }).catch(()=>{});
        }
      }
    } catch (e) {
      TibanXboxBridge.onStreamError('WebRTC Answer Error: ' + e.message);
    }
  };

  window.setXboxMuted = function(muted) {
    video.muted = !!muted;
  };

  window.captureVideoJpeg = function() {
    try {
      if (video.readyState < 2 || video.videoWidth === 0) {
        TibanXboxBridge.onFrameBase64('');
        return;
      }
      ctx.drawImage(video, 0, 0, 1920, 1080);
      const dataUrl = canvas.toDataURL('image/jpeg', 0.86);
      const comma = dataUrl.indexOf(',');
      TibanXboxBridge.onFrameBase64(comma >= 0 ? dataUrl.substring(comma + 1) : '');
    } catch(e) {
      TibanXboxBridge.onFrameBase64('');
    }
  };

  (async function initOffer(){
    try {
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      setTimeout(() => {
        TibanXboxBridge.onLocalOfferReady(pc.localDescription.sdp, JSON.stringify(candidates));
      }, 900);
    } catch (e) {
      TibanXboxBridge.onStreamError('Offer Error: ' + e.message);
    }
  })();
})();
</script>
</body>
</html>
"""
    }
}
