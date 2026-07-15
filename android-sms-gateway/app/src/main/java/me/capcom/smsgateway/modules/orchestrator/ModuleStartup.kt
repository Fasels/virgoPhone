package me.capcom.smsgateway.modules.orchestrator

internal data class ModuleStart(
    val name: String,
    val action: () -> Unit,
)

internal class ModuleStartup {
    private val startedModules = mutableSetOf<String>()

    @Synchronized
    fun start(
        modules: List<ModuleStart>,
        onFailure: (String, Throwable) -> Unit,
    ) {
        modules.forEach { module ->
            if (module.name in startedModules) return@forEach

            try {
                module.action()
                startedModules += module.name
            } catch (error: Throwable) {
                onFailure(module.name, error)
            }
        }
    }

    @Synchronized
    fun reset() {
        startedModules.clear()
    }
}
