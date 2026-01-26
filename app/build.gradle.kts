import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.example.offlinevoice"
  compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
  defaultConfig {
    applicationId = "com.example.offlinevoice"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "0.1"

    // MLC + modern devices: start with arm64 only
    ndk { abiFilters += listOf("arm64-v8a") }

    externalNativeBuild {
      cmake { cppFlags += "-std=c++17" }
    }
  }

  val storeFilePath = project.findProperty("COMPAION_STORE_FILE") as String?
  val storePasswordValue = project.findProperty("COMPAION_STORE_PASSWORD") as String?
  val keyAliasValue = project.findProperty("COMPAION_KEY_ALIAS") as String?
  val keyPasswordValue = project.findProperty("COMPAION_KEY_PASSWORD") as String?
  val hasSigningConfig = listOf(
    storeFilePath,
    storePasswordValue,
    keyAliasValue,
    keyPasswordValue
  ).all { !it.isNullOrBlank() }

  signingConfigs {
    create("release") {
      if (hasSigningConfig) {
        storeFile = file(storeFilePath!!)
        storePassword = storePasswordValue
        keyAlias = keyAliasValue
        keyPassword = keyPasswordValue
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasSigningConfig) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    debug { isJniDebuggable = true }
  }

  externalNativeBuild {
    cmake { path = file("src/main/cpp/CMakeLists.txt") }
  }

  buildFeatures {
    viewBinding = true
    buildConfig = true
  }

  sourceSets["main"].assets.srcDirs(
    "src/main/assets",
    layout.buildDirectory.dir("generated/assets/whisper"),
    layout.buildDirectory.dir("generated/assets/mlc")
  )
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

val whisperModelSrc = file("src/main/cpp/whisper.cpp/models/ggml-base.bin")
val generatedAssetsDir = layout.buildDirectory.dir("generated/assets/whisper")
val generatedMlcAssetsDir = layout.buildDirectory.dir("generated/assets/mlc")

val prepareWhisperModel by tasks.registering(Copy::class) {
  from(whisperModelSrc)
  into(generatedAssetsDir.map { it.dir("models") })
}

tasks.named("preBuild") {
  dependsOn(prepareWhisperModel)
}

val mlcModelId = "Qwen2.5-0.5B-Instruct-q4f16_1-MLC"
val mlcModelLib = "qwen2_q4f16_1_95967267c464e10967be161a66e856d4"
val mlcModelCacheDir = File(
  System.getProperty("user.home"),
  ".cache/mlc_llm/model_weights/hf/mlc-ai/$mlcModelId"
)
val mlcRepoDir = rootProject.projectDir.parentFile.resolve("mlc-llm")
val mlcAppConfig = rootProject.projectDir.resolve("dist/lib/mlc4j/src/main/assets/mlc-app-config.json")
val mlcModelLibTxt = layout.buildDirectory.file("generated/mlc/model_lib.txt")
val mlcGeneratedAppConfig = generatedMlcAssetsDir.map { it.file("mlc-app-config.json") }

val prepareMlcModelLibTxt by tasks.registering {
  outputs.file(mlcModelLibTxt)
  doLast {
    val outFile = mlcModelLibTxt.get().asFile
    outFile.parentFile.mkdirs()
    outFile.writeText(mlcModelLib)
  }
}

val prepareMlcAppConfig by tasks.registering {
  outputs.file(mlcGeneratedAppConfig)
  doLast {
    val outFile = mlcGeneratedAppConfig.get().asFile
    outFile.parentFile.mkdirs()
    val modelUrl = "https://huggingface.co/mlc-ai/$mlcModelId"
    outFile.writeText(
      """
      {
        "model_list": [
          {
            "model_id": "$mlcModelId",
            "model_lib": "$mlcModelLib",
            "model_url": "$modelUrl"
          }
        ]
      }
      """.trimIndent()
    )
  }
}

tasks.named("preBuild") {
  dependsOn(prepareMlcAppConfig)
}

val installMlcModel by tasks.registering {
  dependsOn(prepareMlcModelLibTxt, prepareMlcAppConfig)
  doLast {
    if (!mlcModelCacheDir.exists()) {
      throw GradleException(
        "MLC model cache not found at: ${mlcModelCacheDir.absolutePath}\n" +
          "Run the mlc_llm package step first."
      )
    }
    val deviceRoot = "/sdcard/Android/data/com.example.offlinevoice/files/models/llm"
    exec { commandLine("adb", "shell", "mkdir", "-p", "$deviceRoot/$mlcModelId") }
    exec { commandLine("adb", "push", mlcModelCacheDir.absolutePath, deviceRoot) }
    exec {
      commandLine(
        "adb",
        "push",
        mlcModelLibTxt.get().asFile.absolutePath,
        "$deviceRoot/$mlcModelId/model_lib.txt"
      )
    }
    exec {
      commandLine(
        "adb",
        "push",
        mlcGeneratedAppConfig.get().asFile.absolutePath,
        "$deviceRoot/mlc-app-config.json"
      )
    }
    if (mlcAppConfig.exists()) {
      exec { commandLine("adb", "push", mlcAppConfig.absolutePath, "$deviceRoot/mlc-app-config.repo.json") }
    }
    val permissionScript =
      """
      set -e
      MODEL_ROOT="$deviceRoot"
      chmod 755 "${'$'}MODEL_ROOT" || true
      find "${'$'}MODEL_ROOT" -type d -exec chmod 755 {} + || true
      find "${'$'}MODEL_ROOT" -type f -exec chmod 644 {} + || true
      """.trimIndent()
    exec { commandLine("adb", "shell", "sh", "-c", permissionScript) }
  }
}

tasks.register("installDebugWithModel") {
  dependsOn("installDebug", installMlcModel)
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("io.github.sceneview:sceneview:2.3.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation(project(":mlc4j"))
}
