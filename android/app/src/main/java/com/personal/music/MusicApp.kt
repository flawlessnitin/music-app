package com.personal.music

import android.app.Application

class MusicApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
