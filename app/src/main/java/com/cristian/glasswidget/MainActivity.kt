package com.cristian.glasswidget

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent

import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote

import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse

class MainActivity : AppCompatActivity() {

    private lateinit var previewContainer: RelativeLayout
    private lateinit var radiusSlider: SeekBar
    private lateinit var opacitySlider: SeekBar
    private lateinit var connectButton: Button

    private val clientId = BuildConfig.SPOTIFY_CLIENT_ID
    private val redirectUri = "com.cristian.glasswidget://callback"

    companion object {
        private const val AUTH_TOKEN_REQUEST_CODE = 1337
        private const val TAG = "SpotifyLog"
    }
    private var spotifyAppRemote: SpotifyAppRemote? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupListeners()
        updateGlassEffect()
    }

    private fun initializeViews() {
        previewContainer = findViewById(R.id.widgetPreviewContainer)
        radiusSlider = findViewById(R.id.seekBarRadius)
        opacitySlider = findViewById(R.id.seekBarOpacity)
        connectButton = findViewById(R.id.btnPublish)
    }

    private fun setupListeners() {
        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateGlassEffect()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        radiusSlider.setOnSeekBarChangeListener(sliderListener)
        opacitySlider.setOnSeekBarChangeListener(sliderListener)

        // Ahora el botón inicia el login de Spotify (no conecta directo)
        connectButton.setOnClickListener {
            iniciarLoginSpotify()
        }
    }

    private fun updateGlassEffect() {
        val radius = radiusSlider.progress.toFloat() * 2
        val alpha = (opacitySlider.progress * 255) / 100
        val glassDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.argb(alpha, 255, 255, 255))
            setStroke(2, Color.argb(180, 255, 255, 255))
        }
        previewContainer.background = glassDrawable
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

                spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
                    val track = playerState.track
                    if (track != null) {
                        Log.d(TAG, "Sonando ahora: ${track.name} de ${track.artist.name}")
                    }
                }
            }

            override fun onFailure(throwable: Throwable) {
                Log.e(TAG, "ERROR: No se pudo conectar", throwable)
            }
        })
    }

    // Abrimos el login manualmente (en vez de dejar que App Remote lo haga solo)
    // porque el flujo automático se bloquea en Android 14+ por restricciones de
    // seguridad (Background Activity Launch). Ver README para más detalle.
    private fun iniciarLoginSpotify() {
        val builder = AuthorizationRequest.Builder(clientId, AuthorizationResponse.Type.TOKEN, redirectUri)
        builder.setScopes(arrayOf("app-remote-control", "user-read-private"))
        val request = builder.build()

        AuthorizationClient.openLoginActivity(this, AUTH_TOKEN_REQUEST_CODE, request)
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
}