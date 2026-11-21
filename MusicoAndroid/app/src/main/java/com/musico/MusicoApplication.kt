package com.musico

import android.app.Application
import com.musico.store.AppContainer
import com.musico.store.AppContainerProvider

class MusicoApplication : Application(), AppContainerProvider {
    override val appContainer: AppContainer by lazy {
        AppContainer(applicationContext).also { container ->
            container.playerController.restore()
        }
    }
}
