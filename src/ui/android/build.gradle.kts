plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val pleosVersion = "2.3.2"
android {
    // Optional isolated debug manifest for bounded instrumentation suites.
    providers.gradleProperty("roadGuardianDebugManifest").orNull?.let { path ->
        val selected = rootProject.file(path).canonicalFile
        require(
            selected.isFile && selected.toPath()
                .startsWith(rootProject.projectDir.canonicalFile.toPath())
        )
        sourceSets.getByName("debug").manifest.srcFile(selected)
    }
    namespace = "ai.teraq.roadguardian"
    compileSdk = 35
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = "ai.teraq.roadguardian"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        manifestPlaceholders["appAuthRedirectScheme"] = "ai.teraq.roadguardian"
        for ((field, property) in mapOf(
            "OIDC_ISSUER" to "roadGuardianOidcIssuer",
            "OIDC_CLIENT_ID" to "roadGuardianOidcClientId",
            "AUTH_API_URL" to "roadGuardianAuthenticatedApiUrl"
        )) {
            val value = providers.gradleProperty(property).orElse("").get()
            require(value.none { it == '"' || it == '\\' || it == '\n' || it == '\r' })
            buildConfigField("String", field, "\"$value\"")
        }
        buildConfigField("String", "PLEOS_SDK_VERSION", "\"$pleosVersion\"")
        buildConfigField("boolean", "SYNTHETIC_BACKEND", "false")
        buildConfigField("boolean", "CONTEST_DEMO", "false")
        buildConfigField("boolean", "PLEOS_DEMO_HEADS_UP", "false")
        val reviewEnabled =
            providers.gradleProperty("roadGuardianReviewNotesEnabled").orElse("false").get()
                .toBooleanStrict()
        buildConfigField("boolean", "REVIEW_NOTES_ENABLED", reviewEnabled.toString())
        val observationsEnabled =
            providers.gradleProperty("roadGuardianSdkObservationsEnabled").orElse("false").get()
                .toBooleanStrict()
        buildConfigField("boolean", "SDK_OBSERVATIONS_ENABLED", observationsEnabled.toString())
        val ownerCasesEnabled =
            providers.gradleProperty("roadGuardianOwnerCasesEnabled").orElse("false").get()
                .toBooleanStrict()
        buildConfigField("boolean", "OWNER_CASES_ENABLED", ownerCasesEnabled.toString())
        val photosEnabled =
            providers.gradleProperty("roadGuardianCasePhotosEnabled").orElse("false").get()
                .toBooleanStrict()
        buildConfigField("boolean", "CASE_PHOTOS_ENABLED", photosEnabled.toString())
        buildConfigField(
            "boolean",
            "POWERTRAIN_ENABLED",
            providers.gradleProperty("roadGuardianPowertrainEnabled").orElse("false").get()
                .toBooleanStrict().toString()
        )
        buildConfigField(
            "boolean",
            "MEASUREMENTS_ENABLED",
            providers.gradleProperty("roadGuardianMeasurementsEnabled").orElse("false").get()
                .toBooleanStrict().toString()
        )
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        getByName("debug") {
            buildConfigField(
                "boolean",
                "PLEOS_DEMO_HEADS_UP",
                providers.gradleProperty("roadGuardianPleosDemoHeadsUp").orElse("false").get()
                    .toBooleanStrict().toString()
            )
            buildConfigField("boolean", "SYNTHETIC_BACKEND", "true")
            val endpoint = providers.gradleProperty("roadGuardianDebugBackendUrl")
                .orElse("http://127.0.0.1:8766").get()
            require(
                !endpoint.contains('"') && !endpoint.contains('\\') && !endpoint.contains('\n') && !endpoint.contains(
                    '\r'
                )
            )
            buildConfigField("String", "BACKEND_URL", "\"$endpoint\"")
        }
        getByName("release") {
            val endpoint = providers.gradleProperty("roadGuardianBackendUrl").orElse("").get()
            require(
                !endpoint.contains('"') && !endpoint.contains('\\') && !endpoint.contains('\n') && !endpoint.contains(
                    '\r'
                )
            )
            buildConfigField("String", "BACKEND_URL", "\"$endpoint\"")
        }
        create("integration") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "PLEOS_DEMO_HEADS_UP", "false")
            applicationIdSuffix = ".integration"
            versionNameSuffix = "-local-https-test"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "BACKEND_URL", "\"\"")
            buildConfigField("boolean", "SYNTHETIC_BACKEND", "false")
            for ((field, property) in mapOf(
                "REVIEW_NOTES_ENABLED" to "roadGuardianReviewNotesEnabled",
                "SDK_OBSERVATIONS_ENABLED" to "roadGuardianSdkObservationsEnabled",
                "OWNER_CASES_ENABLED" to "roadGuardianOwnerCasesEnabled",
                "CASE_PHOTOS_ENABLED" to "roadGuardianCasePhotosEnabled"
            ))
                buildConfigField(
                    "boolean",
                    field,
                    providers.gradleProperty(property).orElse("true").get().toBooleanStrict()
                        .toString()
                )
            buildConfigField("String", "AUTH_API_URL", "\"https://localhost:9443\"")
            buildConfigField("String", "OIDC_ISSUER", "\"https://localhost:9443/identity\"")
            buildConfigField("String", "OIDC_CLIENT_ID", "\"local-integration-client\"")
        }
        create("contest") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "CONTEST_DEMO", "true")
            applicationIdSuffix = ".contest"
            versionNameSuffix = "-contest-demo"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "SYNTHETIC_BACKEND", "true")
            buildConfigField("String", "BACKEND_URL", "\"http://127.0.0.1:9460\"")
        }
        create("contestRelease") {
            initWith(getByName("release"))
            isDebuggable = false
            buildConfigField(
                "boolean",
                "PLEOS_DEMO_HEADS_UP",
                providers.gradleProperty("roadGuardianPleosDemoHeadsUp").orElse("false").get()
                    .toBooleanStrict().toString()
            )
            applicationIdSuffix = ".contest.release"
            versionNameSuffix = "-contest-release-candidate"
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "CONTEST_DEMO", "true")
            buildConfigField("boolean", "SYNTHETIC_BACKEND", "true")
            buildConfigField("String", "BACKEND_URL", "\"http://127.0.0.1:9460\"")
        }
    }
    val integrationResources = providers.gradleProperty("roadGuardianIntegrationResources").orNull
    if (integrationResources != null) sourceSets.getByName("integration").res.srcDir(
        integrationResources
    )
    if (gradle.startParameter.taskNames.any { it.contains("integration", ignoreCase = true) })
        require(integrationResources != null) { "Generate local HTTPS fixtures and supply roadGuardianIntegrationResources" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("net.openid:appauth:0.11.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("ai.pleos.playground:Vehicle:$pleosVersion")
    implementation("ai.pleos.playground:vehicle-data:1.1.11")
}
