package br.com.porteirinho

import android.content.Context
import br.com.porteirinho.data.PatrolRepository
import br.com.porteirinho.data.local.AppDatabase
import br.com.porteirinho.domain.DeviceIdentity
import br.com.porteirinho.sync.AdminRemoteClient
import br.com.porteirinho.sync.RemoteSyncClient

class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.create(context)
    val deviceIdentity = DeviceIdentity(context)
    val repository: PatrolRepository = PatrolRepository(database, deviceIdentity)
    val remoteSyncClient: RemoteSyncClient = RemoteSyncClient(context, database, deviceIdentity)
    val adminRemoteClient: AdminRemoteClient = AdminRemoteClient(context, deviceIdentity)
}
