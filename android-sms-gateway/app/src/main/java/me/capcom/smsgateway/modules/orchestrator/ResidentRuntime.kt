package me.capcom.smsgateway.modules.orchestrator

import android.content.Context

interface ResidentRuntime {
    fun start(context: Context)
    fun stop(context: Context)
}
