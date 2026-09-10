package br.com.porteirinho.sync

import android.content.Context
import br.com.porteirinho.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IncrementalSyncCoordinator(
    private val context: Context,
    private val remoteSyncClient: RemoteSyncClient,
) {
    suspend fun pullIfChanged(): Result<Unit> = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_URL.isBlank()) Result.success(Unit)
        else remoteSyncClient.pullSnapshot()
    }
}
