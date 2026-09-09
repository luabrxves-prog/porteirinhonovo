package br.com.porteirinho

import android.app.Application
import br.com.porteirinho.sync.SyncScheduler

class PorteirinhoApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedule(this)
    }
}
