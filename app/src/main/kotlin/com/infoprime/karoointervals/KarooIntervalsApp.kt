package com.infoprime.karoointervals

import android.app.Application
import com.infoprime.karoointervals.data.Engine

class KarooIntervalsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }
}
