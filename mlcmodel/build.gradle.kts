plugins {
  id("com.android.asset-pack")
}

val mlcModelId = "Qwen2.5-0.5B-Instruct-q4f16_1-MLC"
val mlcModelCacheDir = File(
  System.getProperty("user.home"),
  ".cache/mlc_llm/model_weights/hf/mlc-ai/$mlcModelId"
)
val bundledMlcModelDir = file("src/main/assets/mlc/models/$mlcModelId")

assetPack {
  packName = "mlcmodel"
  dynamicDelivery {
    deliveryType = "install-time"
  }
}

val prepareMlcAssetPack by tasks.registering(Copy::class) {
  doFirst {
    if (!mlcModelCacheDir.exists()) {
      throw GradleException(
        "MLC model cache not found at: ${mlcModelCacheDir.absolutePath}\n" +
          "Run the mlc_llm package step first."
      )
    }
  }
  from(mlcModelCacheDir)
  into(bundledMlcModelDir)
}

tasks.configureEach {
  if (name == "prepareMlcAssetPack") return@configureEach
  if (
    name.contains("AssetPack", ignoreCase = true) ||
    name.startsWith("bundle", ignoreCase = true) ||
    name.startsWith("assemble", ignoreCase = true)
  ) {
    dependsOn(prepareMlcAssetPack)
  }
}
