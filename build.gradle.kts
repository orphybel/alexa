// Plugin versions are declared in settings.gradle.kts (pluginManagement.plugins).
// Each module applies the plugins it needs.

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
