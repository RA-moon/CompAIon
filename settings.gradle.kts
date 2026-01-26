include(":mlc4j")
project(":mlc4j").projectDir =
    file("../mlc-llm/android/mlc4j")


pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { google(); mavenCentral() }
}
rootProject.name = "CompAIon"
include(":app")
include(":mlcmodel")
