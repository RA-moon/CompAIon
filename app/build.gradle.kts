import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

val mlcModelId = "Qwen2.5-0.5B-Instruct-q4f16_1-MLC"
val mlcModelLib = "qwen2_q4f16_1_95967267c464e10967be161a66e856d4"
val mlcAssetPackName = "mlcmodel"
val mlcFallbackZipUrl = (project.findProperty("MLC_FALLBACK_ZIP_URL") as String?)
  ?.trim()
  .orEmpty()
val mlcFallbackZipUrlEscaped = mlcFallbackZipUrl
  .replace("\\", "\\\\")
  .replace("\"", "\\\"")

android {
  namespace = "com.example.offlinevoice"
  compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
  defaultConfig {
    applicationId = "com.astralpirates.elsa"
    minSdk = 26
    targetSdk = 34
    versionCode = 4
    versionName = "0.4"
    buildConfigField("String", "MLC_BASE_MODEL_ID", "\"$mlcModelId\"")
    buildConfigField("String", "MLC_ASSET_PACK", "\"$mlcAssetPackName\"")
    buildConfigField("String", "MLC_FALLBACK_ZIP_URL", "\"$mlcFallbackZipUrlEscaped\"")

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

  assetPacks += setOf(":mlcmodel")

  flavorDimensions += "dist"
  productFlavors {
    create("store") {
      dimension = "dist"
    }
    create("sideload") {
      dimension = "dist"
    }
  }

  sourceSets["main"].assets.srcDirs(
    "src/main/assets",
    layout.buildDirectory.dir("generated/assets/whisper"),
    layout.buildDirectory.dir("generated/assets/mlc")
  )
  sourceSets["sideload"].assets.srcDirs(
    layout.buildDirectory.dir("generated/assets/mlc_sideload")
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

val mlcModelCacheDir = File(
  System.getProperty("user.home"),
  ".cache/mlc_llm/model_weights/hf/mlc-ai/$mlcModelId"
)
val mlcRepoDir = rootProject.projectDir.parentFile.resolve("mlc-llm")
val mlcAppConfig = rootProject.projectDir.resolve("dist/lib/mlc4j/src/main/assets/mlc-app-config.json")
val mlcModelLibTxt = layout.buildDirectory.file("generated/mlc/model_lib.txt")
val mlcGeneratedAppConfig = generatedMlcAssetsDir.map { it.file("mlc-app-config.json") }
val appId = android.defaultConfig.applicationId ?: "com.astralpirates.elsa"
val bundledMlcSideloadDir = layout.buildDirectory.dir("generated/assets/mlc_sideload/mlc/models/$mlcModelId")

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
    outFile.parentFile.deleteRecursively()
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

val prepareBundledMlcModelSideload by tasks.registering(Copy::class) {
  doFirst {
    if (!mlcModelCacheDir.exists()) {
      throw GradleException(
        "MLC model cache not found at: ${mlcModelCacheDir.absolutePath}\n" +
          "Run the mlc_llm package step first."
      )
    }
  }
  from(mlcModelCacheDir)
  into(bundledMlcSideloadDir)
}

tasks.named("preBuild") {
  dependsOn(prepareMlcAppConfig)
}

tasks.configureEach {
  if (name.startsWith("preSideload", ignoreCase = true)) {
    dependsOn(prepareBundledMlcModelSideload)
  }
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
    val deviceRoot = "/sdcard/Android/data/$appId/files/models/llm"
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
  implementation("com.google.android.play:asset-delivery:2.2.2")
  implementation("io.github.sceneview:sceneview:2.3.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation(project(":mlc4j"))
}
