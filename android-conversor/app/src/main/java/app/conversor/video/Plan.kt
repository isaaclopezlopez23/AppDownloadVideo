package app.conversor.video

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// Sin dependencias de Android: se prueba en el PC con el ffmpeg de escritorio.

enum class Modo(val titulo: String, val explicacion: String) {
    SONIDO(
        "Arreglar sonido",
        "Rápido. El vídeo no se toca: solo el audio Dolby, DTS o TrueHD pasa a AAC, que suena en cualquier proyector o tele.",
    ),
    MP4(
        "MP4 compatible",
        "Deja un MP4 con vídeo H.264 y audio AAC. Si el vídeo no es H.264 hay que recodificarlo y tarda bastante más.",
    ),
    MP3(
        "Solo audio (MP3)",
        "Saca la pista de audio principal a un MP3.",
    ),
}

enum class Resolucion(val titulo: String, val corto: Int, val largo: Int) {
    ORIGINAL("Original", 0, 0),
    P1080("1080p", 1080, 1920),
    P720("720p", 720, 1280),
    P480("480p", 480, 854),
}

data class Opciones(
    val modo: Modo = Modo.SONIDO,
    /** Pasar a estéreo el audio de 5.1/7.1 que se recodifica (los altavoces del proyector son estéreo). */
    val estereo: Boolean = true,
    val resolucion: Resolucion = Resolucion.ORIGINAL,
    /** Probar primero el codificador por hardware del móvil (mucho más rápido). */
    val hardware: Boolean = true,
    /** Generar el fichero aunque ya sea compatible. */
    val forzar: Boolean = false,
    val kbpsMp3: Int = 192,
)

data class Plan(
    /** Argumentos para ffmpeg, sin el ejecutable. */
    val args: List<String>,
    val extension: String,
    /** Qué se hace con cada pista, en castellano, para el log y la pantalla. */
    val decisiones: List<String>,
    /** Si no hace falta convertir, el motivo. */
    val nadaQueHacer: String? = null,
    val recodificaVideo: Boolean = false,
    val usaHardware: Boolean = false,
)

class PlanImposible(msg: String) : Exception(msg)

object Planificador {
    /** Audio que reproduce cualquier Android sin licencias Dolby/DTS. */
    val AUDIO_COMPATIBLE = setOf("aac", "mp3")

    private val SUBS_TEXTO = setOf("subrip", "srt", "ass", "ssa", "webvtt", "text", "mov_text")
    private val SUBS_MKV_COPIA = setOf(
        "subrip", "srt", "ass", "ssa", "webvtt",
        "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle",
    )

    fun extensionSalida(info: InfoMedia, extEntrada: String, modo: Modo): String = when (modo) {
        Modo.MP3 -> "mp3"
        Modo.MP4 -> "mp4"
        // MKV admite casi cualquier códec, así que es el contenedor seguro al copiar el vídeo tal cual.
        Modo.SONIDO -> if (extEntrada.lowercase(Locale.ROOT) in setOf("mp4", "m4v") && info.esMp4) "mp4" else "mkv"
    }

    fun planificar(
        info: InfoMedia,
        extEntrada: String,
        op: Opciones,
        entrada: String,
        salida: String,
        hardware: Boolean = op.hardware,
    ): Plan {
        val ext = extensionSalida(info, extEntrada, op.modo)
        val decisiones = mutableListOf<String>()
        val salidaArgs = mutableListOf<String>()
        var o = 0 // índice de la pista en el fichero de salida
        var hayCambios = false
        var recodificaVideo = false
        var usaHw = false

        fun mapear(p: Pista) {
            salidaArgs += listOf("-map", "0:${p.indice}")
        }

        fun audio(p: Pista) {
            mapear(p)
            if (p.codec in AUDIO_COMPATIBLE) {
                salidaArgs += listOf("-c:$o", "copy")
                decisiones += "Audio ${p.descripcion()}: ya es compatible, se copia"
            } else {
                hayCambios = true
                val canales = when {
                    p.canales > 2 && op.estereo -> 2
                    p.canales > 6 -> 6 // AAC 7.1 da problemas en muchos reproductores
                    else -> 0
                }
                val finales = if (canales > 0) canales else p.canales
                salidaArgs += listOf("-c:$o", "aac")
                if (canales > 0) salidaArgs += listOf("-ac:$o", "$canales")
                salidaArgs += listOf("-b:$o", when {
                    finales > 2 -> "384k"
                    finales == 1 -> "128k"
                    else -> "192k"
                })
                if (p.frecuencia > 48000) salidaArgs += listOf("-ar:$o", "48000")
                decisiones += "Audio ${p.descripcion()} → AAC ${nombreCanales(finales).ifBlank { "" }}".trimEnd()
            }
            o++
        }

        fun subtitulo(p: Pista, mp4: Boolean) {
            val codec = when {
                mp4 && p.codec == "mov_text" -> "copy"
                mp4 && p.codec in SUBS_TEXTO -> "mov_text"
                mp4 -> null
                p.codec == "mov_text" || p.codec == "text" -> "srt"
                p.codec in SUBS_MKV_COPIA -> "copy"
                else -> null
            }
            if (codec == null) {
                decisiones += "Subtítulos ${p.descripcion()}: se descartan" +
                    if (mp4) " (MP4 no admite subtítulos de imagen)" else " (formato no admitido)"
                return
            }
            mapear(p)
            salidaArgs += listOf("-c:$o", codec)
            decisiones += "Subtítulos ${p.descripcion()}: " + if (codec == "copy") "se copian" else "se pasan a texto ${if (mp4) "MP4" else "SRT"}"
            o++
        }

        when (op.modo) {
            Modo.SONIDO -> {
                if (info.audios.isEmpty()) return sinNada(ext, "No tiene pistas de audio: no hay nada que arreglar")
                val mp4 = ext == "mp4"
                for (p in info.pistas) {
                    when {
                        p.esVideo -> {
                            mapear(p)
                            salidaArgs += listOf("-c:$o", "copy")
                            decisiones += "Vídeo ${p.descripcion()}: se copia tal cual"
                            o++
                        }
                        p.esAudio -> audio(p)
                        p.esSubtitulo -> subtitulo(p, mp4)
                        p.esAdjunto && !mp4 -> {
                            mapear(p)
                            salidaArgs += listOf("-c:$o", "copy")
                            o++
                        }
                    }
                }
                if (!hayCambios && !op.forzar) {
                    return sinNada(ext, "El audio ya es compatible (${info.audios.joinToString { nombreCodec(it.codec) }}): no hace falta convertirlo")
                }
            }

            Modo.MP4 -> {
                val v = info.videos.firstOrNull()
                    ?: throw PlanImposible("El fichero no tiene vídeo. Usa el modo \"Solo audio (MP3)\".")
                val corto = min(v.ancho, v.alto)
                val largo = max(v.ancho, v.alto)
                val r = op.resolucion
                val cabe = r == Resolucion.ORIGINAL || corto == 0 || (corto <= r.corto && largo <= r.largo)
                val h264Bien = v.codec == "h264" &&
                    (v.pixFmt.isEmpty() || v.pixFmt == "yuv420p" || v.pixFmt == "yuvj420p") &&
                    listOf("10", "4:2:2", "4:4:4").none { v.perfil.contains(it) }

                mapear(v)
                if (h264Bien && cabe) {
                    salidaArgs += listOf("-c:$o", "copy")
                    decisiones += "Vídeo ${v.descripcion()}: ya es H.264, se copia tal cual"
                } else {
                    hayCambios = true
                    recodificaVideo = true
                    val factor = if (cabe || corto == 0) 1.0
                    else min(1.0, min(r.corto.toDouble() / corto, r.largo.toDouble() / largo))
                    // yadif solo actúa en fotogramas entrelazados (TDT, DVD); en el resto no hace nada.
                    val filtros = mutableListOf("yadif=deint=interlaced")
                    if (!cabe) {
                        // Escala por el lado corto y el largo, sin ampliar nunca y respetando vídeos en vertical.
                        filtros += "scale=w='trunc(iw*min(1,min(${r.corto}/min(iw,ih),${r.largo}/max(iw,ih)))/2)*2':h=-2"
                    }
                    salidaArgs += listOf("-filter:$o", filtros.joinToString(","))
                    if (hardware) {
                        usaHw = true
                        val pixeles = v.ancho.toDouble() * v.alto * factor * factor
                        salidaArgs += listOf("-c:$o", "h264_mediacodec", "-b:$o", bitrateHw(pixeles))
                    } else {
                        salidaArgs += listOf("-c:$o", "libx264", "-preset", "veryfast", "-crf", "23")
                    }
                    salidaArgs += listOf("-pix_fmt:$o", "yuv420p")
                    val destino = if (cabe) "" else " a ${r.titulo}"
                    decisiones += "Vídeo ${v.descripcion()} → H.264$destino" +
                        if (hardware) " (codificador del móvil)" else " (por software, tarda)"
                }
                o++
                for (p in info.audios) audio(p)
                for (p in info.subtitulos) subtitulo(p, mp4 = true)
                info.videos.drop(1).forEach { decisiones += "Vídeo extra ${it.descripcion()}: se descarta" }

                val yaEsMp4 = info.esMp4 && extEntrada.lowercase(Locale.ROOT) in setOf("mp4", "m4v")
                if (!hayCambios && yaEsMp4 && !op.forzar) {
                    return sinNada(ext, "Ya es un MP4 con vídeo H.264 y audio compatible: no hace falta convertirlo")
                }
            }

            Modo.MP3 -> {
                val a = info.audios.firstOrNull { it.porDefecto } ?: info.audios.firstOrNull()
                    ?: throw PlanImposible("El fichero no tiene audio.")
                mapear(a)
                if (a.codec == "mp3") {
                    salidaArgs += listOf("-c:0", "copy")
                    decisiones += "Audio ${a.descripcion()}: ya es MP3, se extrae sin recodificar"
                } else {
                    salidaArgs += listOf("-c:0", "libmp3lame", "-b:0", "${op.kbpsMp3}k")
                    if (a.canales > 2) salidaArgs += listOf("-ac:0", "2")
                    if (a.frecuencia > 48000) salidaArgs += listOf("-ar:0", "48000")
                    decisiones += "Audio ${a.descripcion()} → MP3 ${op.kbpsMp3} kbps"
                }
                if (info.audios.size > 1) decisiones += "Hay ${info.audios.size} pistas de audio: se usa la principal"
                o = 1
            }
        }

        val args = mutableListOf("-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-progress", "pipe:1", "-nostats")
        // Los AVI antiguos (DivX/Xvid) suelen traer marcas de tiempo incompletas.
        if (extEntrada.lowercase(Locale.ROOT) == "avi") args += listOf("-fflags", "+genpts")
        args += listOf("-probesize", "50M", "-analyzeduration", "50M", "-i", entrada)
        args += salidaArgs
        when (ext) {
            "mp3" -> args += listOf("-map_metadata", "0", "-id3v2_version", "3", "-f", "mp3")
            "mp4" -> args += listOf(
                "-map_metadata", "0", "-map_chapters", "0", "-max_muxing_queue_size", "9999",
                "-movflags", "+faststart", "-f", "mp4",
            )
            else -> args += listOf(
                "-map_metadata", "0", "-map_chapters", "0", "-max_muxing_queue_size", "9999", "-f", "matroska",
            )
        }
        args += salida
        return Plan(args, ext, decisiones, null, recodificaVideo, usaHw)
    }

    private fun sinNada(ext: String, motivo: String) = Plan(emptyList(), ext, emptyList(), motivo)

    /** Los codificadores por hardware no tienen modo "calidad constante": se les da un bitrate holgado. */
    fun bitrateHw(pixeles: Double): String = when {
        pixeles > 4_000_000 -> "16M"
        pixeles > 1_800_000 -> "8M"
        pixeles > 800_000 -> "5M"
        pixeles > 350_000 -> "2500k"
        else -> "1500k"
    }
}
