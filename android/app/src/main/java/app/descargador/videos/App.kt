package app.descargador.videos

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Motor.inicializar(this)
    }
}
