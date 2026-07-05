package com.cristian.glasswidget

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.RelativeLayout
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // UI Components
    private lateinit var previewContainer: RelativeLayout
    private lateinit var radiusSlider: SeekBar
    private lateinit var opacitySlider: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupListeners()

        // Aplicar el estado inicial del widget basado en los valores por defecto de los sliders
        updateGlassEffect()
    }

    private fun initializeViews() {
        previewContainer = findViewById(R.id.widgetPreviewContainer)
        radiusSlider = findViewById(R.id.seekBarRadius)
        opacitySlider = findViewById(R.id.seekBarOpacity)
    }

    private fun setupListeners() {
        // Listener anónimo para actualizar el UI dinámicamente al deslizar
        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateGlassEffect()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        radiusSlider.setOnSeekBarChangeListener(sliderListener)
        opacitySlider.setOnSeekBarChangeListener(sliderListener)
    }

    private fun updateGlassEffect() {
        // Multiplicamos por 2 para exagerar el radio visual en la demostración
        val radius = radiusSlider.progress.toFloat() * 2

        // Mapeo de porcentaje (0-100) a valor Alpha de Android (0-255)
        val alpha = (opacitySlider.progress * 255) / 100

        // Generación dinámica del fondo Glassmorphism
        val glassDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.argb(alpha, 255, 255, 255))
            setStroke(2, Color.argb(180, 255, 255, 255)) // Borde brillante para simular reflexión
        }

        previewContainer.background = glassDrawable
    }
}