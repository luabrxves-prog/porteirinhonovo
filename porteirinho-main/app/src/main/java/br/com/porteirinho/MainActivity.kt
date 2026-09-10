package br.com.porteirinho

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.porteirinho.ui.AppViewModel
import br.com.porteirinho.ui.PorteirinhoApp
import br.com.porteirinho.ui.theme.PorteirinhoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as PorteirinhoApplication).container
        setContent {
            PorteirinhoTheme {
                val appViewModel: AppViewModel = viewModel(
                    factory = AppViewModel.Factory(
                        container.repository,
                        container.remoteSyncClient,
                        container.adminRemoteClient,
                    ),
                )
                PorteirinhoApp(appViewModel)
            }
        }
    }
}
