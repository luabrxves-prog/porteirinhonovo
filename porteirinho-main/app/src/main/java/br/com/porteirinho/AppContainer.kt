package br.com.porteirinho

import android.content.Context
import br.com.porteirinho.data.PatrolRepository
import br.com.porteirinho.data.local.AppDatabase
import br.com.porteirinho.domain.DeviceIdentity

class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.create(context)
    val repository: PatrolRepository = PatrolRepository(database, DeviceIdentity(context))
}
