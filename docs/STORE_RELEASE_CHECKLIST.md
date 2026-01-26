## CompAIon — Distribution Checklist (APK + Play Store)

This checklist captures what’s needed to distribute CompAIon as a signed APK or on the Play Store.

### 1) Signed APK (sideload)
- Create a release keystore (do **not** commit it).
- Add signing config via `gradle.properties` (local only) or CI secrets.
- Build a signed release APK or AAB.

**Example local signing (do not commit secrets):**
- `~/.gradle/gradle.properties`:
  - `COMPAION_STORE_FILE=/path/to/keystore.jks`
  - `COMPAION_STORE_PASSWORD=...`
  - `COMPAION_KEY_ALIAS=...`
  - `COMPAION_KEY_PASSWORD=...`

**Build commands:**
- `./gradlew :app:assembleRelease`
- `./gradlew :app:bundleRelease` (preferred for Store)

### 2) Play Store prerequisites
- Package ID must be final (changing it later breaks updates).
- Versioning: increment `versionCode` and `versionName`.
- Release build should have:
  - `minifyEnabled true`
  - `shrinkResources true`
  - Proguard/R8 rules updated for JNI + MLC runtime.
- App icon, screenshots, feature graphic, and a short demo.
- Privacy Policy + Data Safety form (even on-device apps must declare what they do).
- If you changed the package name, create a **new app** in Play Console before upload.

### 3) Model distribution strategy (critical)
Current plan: **keep the model inside the APK**.
- This is fine for sideloaded APKs, but Play Store size limits still apply.
- If the APK/AAB is too large, switch to Play Asset Delivery (PAD).
- Provide integrity checks and clear user messaging if model is missing.
- The app now extracts bundled assets from `assets/mlc/models` into internal storage on first run.

### 4) Legal/licensing
- Verify licensing for:
  - Whisper model and weights
  - MLC model and weights
  - Any bundled assets
- Add license attribution or links in the app and repo.

### 5) Stability & UX
- Model-not-found should show a clean setup screen (not just a log error).
- Handle slow CPU inference gracefully (progress states, cancel).
- Add a clear offline‑first statement in the UI or onboarding.

### 6) Recommended QA
- Test on low‑RAM devices.
- Test airplane mode start‑to‑finish.
- Validate model install path on Android 11+ (scoped storage).

### 7) Store listing copy (starter)
- Title: `E.L.S.A.`
- Short description (user-provided): `Ahoi!! Local AI runing offline on android - Is she fast? No! - Is it usefull? You tell me! -Do I have fun? Absolutly!`
- Note: Play Store short description is limited (80 chars); trim if needed.

### 8) Release‑build notes for this repo
- `app/build.gradle.kts` reads signing values from Gradle properties when provided.
- `minifyEnabled` + `shrinkResources` are enabled for release.
- JNI keep rules are in `app/proguard-rules.pro`.
