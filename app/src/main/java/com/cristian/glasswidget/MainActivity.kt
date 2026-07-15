package com.cristian.glasswidget

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.view.View
import android.os.Handler
import android.os.Looper
import androidx.palette.graphics.Palette
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import android.widget.LinearLayout
import android.widget.FrameLayout

import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse


class MainActivity : AppCompatActivity() {

    // ================= Vistas =================
    private lateinit var connectScreen: LinearLayout
    private lateinit var liveScreen: LinearLayout
    private lateinit var connectButton: Button

    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var albumArt: ImageView

    // PrismalSlider en vez de SeekBar: renderiza el thumb con efecto de vidrio (Prismal library)
    private lateinit var progressBar: com.matrix.prismal.PrismalSlider
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var rootBackground: FrameLayout

    // ================= Estado del reproductor =================
    private val progressHandler = Handler(Looper.getMainLooper())
    private var lastKnownPosition = 0L
    private var trackDuration = 0L
    private var lastUpdateTimestamp = 0L
    private var isPaused = false
    private var spotifyAppRemote: SpotifyAppRemote? = null

    // ================= Config Spotify =================
    private val clientId = BuildConfig.SPOTIFY_CLIENT_ID
    private val redirectUri = "com.cristian.glasswidget://callback"


    // ================= Ciclo de vida de Activity =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupListeners()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        if (requestCode == AUTH_TOKEN_REQUEST_CODE) {
            val response = AuthorizationClient.getResponse(resultCode, intent)

            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    Log.d(TAG, "Token recibido y conectamos App Remote.")
                    connectToSpotify()
                }
                AuthorizationResponse.Type.ERROR -> {
                    Log.e(TAG, "Error de autorización: ${response.error}")
                }
                else -> {
                    Log.d(TAG, "El usuario canceló el login")
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
            Log.d(TAG, "Desconectado de Spotify (Ahorrando batería)")
        }
    }


    // ================= Setup inicial =================

    private fun initializeViews() {
        rootBackground = findViewById(R.id.rootBackground)
        connectScreen = findViewById(R.id.connectScreen)
        liveScreen = findViewById(R.id.liveScreen)
        connectButton = findViewById(R.id.connectButton)

        trackTitle = findViewById(R.id.trackTitle)
        trackArtist = findViewById(R.id.trackArtist)
        albumArt = findViewById(R.id.albumArt)

        progressBar = findViewById(R.id.progressBar)
        currentTimeText = findViewById(R.id.currentTime)
        totalTimeText = findViewById(R.id.totalTime)
    }

    private fun setupListeners() {
        connectButton.setOnClickListener {
            iniciarLoginSpotify()
        }
    }


    // ================= Autenticación con Spotify =================

    // Abrimos el login manualmente (en vez de dejar que App Remote lo haga solo)
    // porque el flujo automático se bloquea en Android 14+ por restricciones de
    // seguridad (Background Activity Launch). Ver README para más detalle.
    private fun iniciarLoginSpotify() {
        val builder = AuthorizationRequest.Builder(clientId, AuthorizationResponse.Type.TOKEN, redirectUri)
        builder.setScopes(arrayOf("app-remote-control", "user-read-private"))
        val request = builder.build()

        AuthorizationClient.openLoginActivity(this, AUTH_TOKEN_REQUEST_CODE, request)
    }

    private fun connectToSpotify() {
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            // false porque ya obtuvimos el token nosotros mismos con iniciarLoginSpotify().
            // Si esto fuera true, el SDK intentaría abrir su propia ventana de permiso
            // desde un servicio en segundo plano, lo cual Android bloquea desde API 34+ (bug conocido de Spotify SDK).
            .showAuthView(false)
            .build()

        Log.d(TAG, "Intentando conectar a Spotify...")
        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {

            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d(TAG, "Conectado a Spotify de forma local.")

                // Ya conectados: ocultamos la pantalla de "Conectar" y mostramos la vista en vivo
                connectScreen.visibility = View.GONE
                liveScreen.visibility = View.VISIBLE

                spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
                    val track = playerState.track
                    if (track != null) {
                        Log.d(TAG, "Sonando ahora: ${track.name} de ${track.artist.name}")

                        trackTitle.text = track.name
                        trackArtist.text = track.artist.name

                        // Guardamos la posición/duración reales que nos dio Spotify en este instante
                        trackDuration = track.duration
                        lastKnownPosition = playerState.playbackPosition
                        lastUpdateTimestamp = System.currentTimeMillis()
                        isPaused = playerState.isPaused

                        totalTimeText.text = formatTime(trackDuration)

                        spotifyAppRemote?.imagesApi
                            ?.getImage(track.imageUri)
                            ?.setResultCallback { bitmap ->
                                albumArt.setImageBitmap(bitmap)
                                applyDynamicBackground(bitmap)
                            }
                    }
                }

                startProgressTicker()
            }

            override fun onFailure(throwable: Throwable) {
                Log.e(TAG, "ERROR: No se pudo conectar", throwable)
            }
        })
    }


    // ================= Progreso de reproducción =================

    private val progressRunnable = object : Runnable {
        override fun run() {
            val estimatedPosition = if (isPaused) {
                lastKnownPosition
            } else {
                val elapsed = System.currentTimeMillis() - lastUpdateTimestamp
                lastKnownPosition + elapsed
            }

            if (trackDuration > 0) {
                val progressPercent = ((estimatedPosition * 100) / trackDuration).toInt()
                progressBar.setValue(progressPercent.coerceIn(0, 100).toFloat())
                currentTimeText.text = formatTime(estimatedPosition)
            }

            progressHandler.postDelayed(this, 500)
        }
    }

    private fun startProgressTicker() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }


    // ================= Fondo dinámico según la portada =================

    private fun applyDynamicBackground(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            val dominantColor = palette?.getDominantColor(Color.parseColor("#0A0A0A"))
                ?: Color.parseColor("#0A0A0A")
            val darkMutedColor = palette?.getDarkMutedColor(Color.parseColor("#000000"))
                ?: Color.parseColor("#000000")

            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(dominantColor, darkMutedColor)
            )
            rootBackground.background = gradient

            progressBar.updateBackground()
        }
    }


    // ================= Constantes =================

    companion object {
        private const val AUTH_TOKEN_REQUEST_CODE = 1337
        private const val TAG = "SpotifyLog"
    }
}