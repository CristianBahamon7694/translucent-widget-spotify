# Glass Widget Builder

Una app Android que se conecta en tiempo real a Spotify y te permite
personalizar visualmente una tarjeta "Now Playing" con estética
**Liquid Glass** (vidrio esmerilado translúcido, estilo iOS) — ajustando
transparencia, radio de esquinas y tamaño mediante controles deslizantes.

## Qué hace

- Autenticación real con la cuenta de Spotify del usuario
- Conexión en tiempo real vía Spotify App Remote SDK (sin polling, eventos push)
- Muestra canción, artista y portada actuales, actualizados en vivo
- Editor visual: opacidad, esquinas y tamaño de la tarjeta, en tiempo real

## Roadmap

- [ ] Selector de color/paleta para la tarjeta
- [ ] Controles de reproducción (play/pause/siguiente) desde la propia tarjeta
- [ ] Publicar la tarjeta como widget nativo de pantalla de inicio
      (`AppWidgetProvider` + servicio en segundo plano)

## Problema resuelto: Bloqueo de autenticación de Spotify en Android 14+

Durante la integración con el Spotify App Remote SDK, la conexión se quedaba
colgada indefinidamente sin disparar ni éxito ni error.

**Diagnóstico:** revisando Logcat, se encontró el mensaje `Background activity
launch blocked!` — parte del sistema BAL (Background Activity Launch) que
Android endurece desde la versión 14. El SDK de Spotify intenta abrir su
ventana de autorización desde un servicio en segundo plano, lo cual el
sistema operativo bloquea por seguridad.

Se confirmó que es un problema conocido del SDK revisando
[spotify/android-sdk#377](https://github.com/spotify/android-sdk/issues/377),
donde otro desarrollador reportó exactamente el mismo comportamiento.

**Solución:** en vez de depender del flujo de autenticación integrado en
App Remote (`showAuthView(true)`), se usa la librería `com.spotify.android:auth`
para abrir el login directamente desde la actividad en primer plano
(`AuthorizationClient.openLoginActivity`). Como el lanzamiento viene de una
interacción directa del usuario, Android no lo bloquea. Una vez se obtiene
el token, se conecta el App Remote pasando `showAuthView(false)`.

## Stack técnico

- Kotlin, Android SDK 31-36
- Spotify App Remote SDK (conexión y datos en tiempo real)
- Spotify Auth Library (autenticación)
- Gradle Kotlin DSL

## Configuración local

Este proyecto usa `local.properties` (no versionado) para tu Spotify Client ID:
spotify.client.id=TU_CLIENT_ID_AQUI

Debes registrar tu propia app en el
[Spotify Developer Dashboard](https://developer.spotify.com/dashboard),
agregando el redirect URI `com.cristian.glasswidget://callback` y tu SHA1
de firma en "Android packages".
