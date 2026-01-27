import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

val coreKtxVersion: String by project
val appcompatVersion: String by project
val materialVersion: String by project
val assetDeliveryVersion: String by project
val sceneviewVersion: String by project
val coroutinesAndroidVersion: String by project

val mlcModelId = "Qwen2.5-0.5B-Instruct-q4f16_1-MLC"
val mlcModelLib = "qwen2_q4f16_1_95967267c464e10967be161a66e856d4"
val mlcAssetPackName = "mlcmodel"
val mlcFallbackZipUrl = (project.findProperty("MLC_FALLBACK_ZIP_URL") as String?)
  ?.trim()
  .orEmpty()
val mlcFallbackZipUrlEscaped = mlcFallbackZipUrl
  .replace("\\", "\\\\")
  .replace("\"", "\\\"")
val modelsDir = "models/llm"
val modelLibFileName = "model_lib.txt"
val appConfigFileName = "mlc-app-config.json"

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
  group = "assets"
  description = "Copy the bundled Whisper model into generated assets."
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
val mlcAppConfig = rootProject.projectDir.resolve("dist/lib/mlc4j/src/main/assets/$appConfigFileName")
val mlcModelLibTxt = layout.buildDirectory.file("generated/mlc/$modelLibFileName")
val mlcGeneratedAppConfig = generatedMlcAssetsDir.map { it.file(appConfigFileName) }
val appId = android.defaultConfig.applicationId ?: "com.astralpirates.elsa"
val bundledMlcSideloadDir = layout.buildDirectory.dir("generated/assets/mlc_sideload/mlc/models/$mlcModelId")

val prepareMlcModelLibTxt by tasks.registering {
  group = "mlc"
  description = "Generate the MLC model_lib marker file."
  outputs.file(mlcModelLibTxt)
  doLast {
    val outFile = mlcModelLibTxt.get().asFile
    outFile.parentFile.mkdirs()
    outFile.writeText(mlcModelLib)
  }
}

val prepareMlcAppConfig by tasks.registering {
  group = "mlc"
  description = "Generate the app-level MLC config JSON."
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
  group = "mlc"
  description = "Bundle the cached MLC model into sideload assets."
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
  group = "mlc"
  description = "Push the configured MLC model and config to a connected device."
  dependsOn(prepareMlcModelLibTxt, prepareMlcAppConfig)
  doLast {
    if (!mlcModelCacheDir.exists()) {
      throw GradleException(
        "MLC model cache not found at: ${mlcModelCacheDir.absolutePath}\n" +
          "Run the mlc_llm package step first."
      )
    }
    val deviceRoot = "/sdcard/Android/data/$appId/files/$modelsDir"
    exec { commandLine("adb", "shell", "mkdir", "-p", "$deviceRoot/$mlcModelId") }
    exec { commandLine("adb", "push", mlcModelCacheDir.absolutePath, deviceRoot) }
    exec {
      commandLine(
        "adb",
        "push",
        mlcModelLibTxt.get().asFile.absolutePath,
        "$deviceRoot/$mlcModelId/$modelLibFileName"
      )
    }
    exec {
      commandLine(
        "adb",
        "push",
        mlcGeneratedAppConfig.get().asFile.absolutePath,
        "$deviceRoot/$appConfigFileName"
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
  group = "mlc"
  description = "Install the debug build and push the configured MLC model."
  dependsOn("installDebug", installMlcModel)
}

dependencies {
  implementation("androidx.core:core-ktx:$coreKtxVersion")
  implementation("androidx.appcompat:appcompat:$appcompatVersion")
  implementation("com.google.android.material:material:$materialVersion")
  implementation("com.google.android.play:asset-delivery:$assetDeliveryVersion")
  implementation("io.github.sceneview:sceneview:$sceneviewVersion")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesAndroidVersion")
  implementation(project(":mlc4j"))
}
