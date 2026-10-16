package it.goletto.iride

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import it.goletto.iride.ui.IrideApp
import it.goletto.iride.ui.theme.IrideTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // scena della guida (emulatore su GitHub): niente finestra dei permessi sopra la schermata
        val scene = intent?.getStringExtra("scene")
        if (scene != null) {
            if (savedInstanceState == null) vm.runScene(scene)
        } else {
            val perms = if (Build.VERSION.SDK_INT >= 33) {
                // notifiche: avanzamento dell'intervallometro a schermo spento
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            permissionLauncher.launch(perms)
        }

        setContent {
            IrideTheme(night = vm.night) {
                val keepOn = vm.keepScreenOn
                LaunchedEffect(keepOn) {
                    if (keepOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                IrideApp(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppVisible(true)
    }

    override fun onStop() {
        // una rotazione dello schermo non è un'uscita dall'app: il live view resta acceso
        if (!isChangingConfigurations) vm.onAppVisible(false)
        super.onStop()
    }
}
