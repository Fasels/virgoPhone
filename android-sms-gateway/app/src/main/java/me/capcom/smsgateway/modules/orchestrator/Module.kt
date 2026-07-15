package me.capcom.smsgateway.modules.orchestrator

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val orchestratorModule = module {
    singleOf(::OrchestratorService)
    single<ResidentRuntime> { get<OrchestratorService>() }
    singleOf(::EventsRouter)
}

val MODULE_NAME = "orchestrator"
