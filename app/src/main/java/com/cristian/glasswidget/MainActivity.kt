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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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

    private lateinit var progressBar: com.matrix.prismal.PrismalSlider
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var rootBackground: FrameLayout

    private val progressHandler = Handler(Looper.getMainLooper())
    private val seekDebounceHandler = Handler(Looper.getMainLooper())
    private var pendingSeekRunnable: Runnable? = null

    private var lastKnownPosition = 0L
    private var trackDuration = 0L
    private var lastUpdateTimestamp = 0L
    private var isPaused = false
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var isProgrammaticUpdate = false
    private var currentTrackUri: String? = null

    // Guarda lo último mostrado, para restaurar la UI tras un giro sin re-consultar a Spotify
    private var lastTrackName: String? = null
    private var lastArtistName: String? = null
    private var lastAlbumBitmap: Bitmap? = null

    private val clientId = BuildConfig.SPOTIFY_CLIENT_ID
    private val redirectUri = "com.cristian.glasswidget://callback"

    private var currentGradientColors: IntArray? = null


    // ================= Ciclo de vida de Activity =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupListeners()
        hideSystemUI()

        // Restauramos el estado tras un giro de pantalla (Activity recreada)
        savedInstanceState?.let { state ->
            val wasShowingLiveScreen = state.getBoolean("wasShowingLiveScreen", false)
            if (wasShowingLiveScreen) {
                connectScreen.visibility = View.GONE
                liveScreen.visibility = View.VISIBLE

                lastTrackName = state.getString("lastTrackName")
                lastArtistName = state.getString("lastArtistName")
                @Suppress("DEPRECATION")
                lastAlbumBitmap = state.getParcelable("lastAlbumBitmap")

                lastTrackName?.let { trackTitle.text = it }
                lastArtistName?.let { trackArtist.text = it }
                lastAlbumBitmap?.let {
                    albumArt.setImageBitmap(it)
                    applyDynamicBackground(it)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
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

    override fun onStart() {
        super.onStart()
        if (liveScreen.visibility == View.VISIBLE) {
            connectToSpotify()
        }
    }

    override fun onStop() {
        super.onStop()
        progressHandler.removeCallbacks(progressRunnable)
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
            Log.d(TAG, "Desconectado de Spotify (Ahorrando batería)")
        }
        spotifyAppRemote = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("wasShowingLiveScreen", ::liveScreen.isInitialized && liveScreen.visibility == View.VISIBLE)
        outState.putString("lastTrackName", lastTrackName)
        outState.putString("lastArtistName", lastArtistName)
        lastAlbumBitmap?.let { outState.putParcelable("lastAlbumBitmap", it) }
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

        progressBar.setOnValueChangedListener { newValue ->
            if (isProgrammaticUpdate) return@setOnValueChangedListener

            if (trackDuration > 0) {
                val seekPositionMs = ((newValue / 100f) * trackDuration).toLong()

                lastKnownPosition = seekPositionMs
                lastUpdateTimestamp = System.currentTimeMillis()
                currentTimeText.text = formatTime(seekPositionMs)

                pendingSeekRunnable?.let { seekDebounceHandler.removeCallbacks(it) }

                pendingSeekRunnable = Runnable {
                    spotifyAppRemote?.playerApi?.seekTo(seekPositionMs)
                    Log.d(TAG, "Seek aplicado a: ${formatTime(seekPositionMs)}")
                }
                seekDebounceHandler.postDelayed(pendingSeekRunnable!!, 200)
            }
        }
    }


    // ================= Autenticacion con Spotify =================

    private fun iniciarLoginSpotify() {
        val builder = AuthorizationRequest.Builder(clientId, AuthorizationResponse.Type.TOKEN, redirectUri)
        builder.setScopes(arrayOf("app-remote-control", "user-read-private"))
        val request = builder.build()

        AuthorizationClient.openLoginActivity(this, AUTH_TOKEN_REQUEST_CODE, request)
    }

    private fun connectToSpotify() {
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(false)
            .build()

        Log.d(TAG, "Intentando conectar a Spotify...")
        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {

            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d(TAG, "Conectado a Spotify de forma local.")

                connectScreen.visibility = View.GONE
                liveScreen.visibility = View.VISIBLE

                spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
                    val track = playerState.track
                    if (track != null) {
                        Log.d(TAG, "Sonando ahora: ${track.name} de ${track.artist.name}")

                        trackDuration = track.duration
                        lastKnownPosition = playerState.playbackPosition
                        lastUpdateTimestamp = System.currentTimeMillis()
                        isPaused = playerState.isPaused

                        totalTimeText.text = formatTime(trackDuration)

                        if (track.uri != currentTrackUri) {
                            currentTrackUri = track.uri

                            spotifyAppRemote?.imagesApi
                                ?.getImage(track.imageUri)
                                ?.setResultCallback { bitmap ->
                                    val cleanTitle = formatToTitleCase(track.name)
                                    val cleanArtist = formatToTitleCase(track.artist.name)

                                    lastTrackName = cleanTitle
                                    lastArtistName = cleanArtist
                                    lastAlbumBitmap = bitmap

                                    animateTrackChange(cleanTitle, cleanArtist, bitmap)
                                    applyDynamicBackground(bitmap)
                                }
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

                isProgrammaticUpdate = true
                progressBar.setValue(progressPercent.coerceIn(0, 100).toFloat())
                isProgrammaticUpdate = false

                currentTimeText.text = formatTime(estimatedPosition)
            }

            progressHandler.postDelayed(this, 500)
        }
    }

    private fun startProgressTicker() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            // Oculta la barra de estado (arriba) y la de navegación (abajo)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // Si el usuario desliza desde el borde, las barras aparecen temporalmente y luego se ocultan solas
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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

            val newColors = intArrayOf(dominantColor, darkMutedColor)

            if (currentGradientColors == null) {
                // Primera vez (recién conectamos): sin animación, se pinta directo
                val gradient = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    newColors
                )
                rootBackground.background = gradient
            } else {
                // Ya había un fondo antes: animamos la transición de color
                animateGradientChange(currentGradientColors!!, newColors)
            }

            currentGradientColors = newColors
            progressBar.updateBackground()
        }
    }

    private fun animateGradientChange(oldColors: IntArray, newColors: IntArray) {
        // Creamos un solo animador que va del 0% (0f) al 100% (1f) de la animación
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)

        // Duración de la transición de color del fondo (en milisegundos)
        animator.duration = 800L

        val evaluator = android.animation.ArgbEvaluator()

        animator.addUpdateListener { animation ->
            val fraction = animation.animatedFraction

            // Calculamos el color exacto en este milisegundo para ambos lados
            val topColor = evaluator.evaluate(fraction, oldColors[0], newColors[0]) as Int
            val bottomColor = evaluator.evaluate(fraction, oldColors[1], newColors[1]) as Int

            // Aplicamos el nuevo fondo
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(topColor, bottomColor)
            )
            rootBackground.background = gradient
        }

        animator.start()
    }

    private fun animateTrackChange(newTitle: String, newArtist: String, newBitmap: Bitmap) {
        val fadeOutDuration = 150L
        val fadeInDuration = 200L

        val viewsToFade = listOf(albumArt, trackTitle, trackArtist)

        viewsToFade.forEach { view ->
            view.animate()
                .alpha(0f)
                .setDuration(fadeOutDuration)
                .withEndAction {
                    when (view) {
                        albumArt -> albumArt.setImageBitmap(newBitmap)
                        trackTitle -> trackTitle.text = newTitle
                        trackArtist -> trackArtist.text = newArtist
                    }
                    view.animate()
                        .alpha(1f)
                        .setDuration(fadeInDuration)
                        .start()
                }
                .start()
        }
    }

    // ================= Limpieza de Texto =================

    private fun formatToTitleCase(text: String): String {
        return text.lowercase().split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }


    // ================= Constantes =================

    companion object {
        private const val AUTH_TOKEN_REQUEST_CODE = 1337
        private const val TAG = "SpotifyLog"
    }
}