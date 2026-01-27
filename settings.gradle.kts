include(":mlc4j")
project(":mlc4j").projectDir =
    file("../mlc-llm/android/mlc4j")


pluginManagement {
  val agpVersion = providers.gradleProperty("agpVersion").get()
  val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
  plugins {
    id("com.android.application") version agpVersion
    id("com.android.library") version agpVersion
    id("org.jetbrains.kotlin.android") version kotlinVersion
    id("org.jetbrains.kotlin.plugin.serialization") version kotlinVersion
  }
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { google(); mavenCentral() }
}
rootProject.name = "CompAIon"
include(":app")
include(":mlcmodel")
