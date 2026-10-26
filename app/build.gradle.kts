import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// The release keystore lives outside the repo. Losing it means the app can no longer be
// upgraded in place, and uninstalling wipes all local data.
val keystorePropsFile = file(
    System.getenv("ASSET_BOARD_KEYSTORE_PROPS")
        ?: "${System.getProperty("user.home")}/.config/asset-board/keystore.properties",
)

android {
    namespace = "com.assetboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.assetboard"
        minSdk = 29
        targetSdk = 36
        versionCode = 26
        versionName = "1.0.2"
        // Where the in-app updater looks for releases; a fork points this at its own repository.
        buildConfigField("String", "UPDATE_REPO", "\"konghw-git/OpenAssetBoard\"")
        // The commit a build came from, for 导出诊断 and for reading a release's crash stacks
        // against its mapping file; "unknown" outside a git checkout.
        val commit = runCatching {
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"
        buildConfigField("String", "COMMIT_SHA", "\"$commit\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            val p = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Separate package so a debug build can never replace (and wipe) the release install.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // What lint found when it was switched on is frozen in the baseline; anything new fails
    // lintRelease, which the release script runs before building.
    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }

    // MigrationTestHelper reads the exported Room schemas from the app's assets under Robolectric;
    // debug builds carry them, release builds do not.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric downloads its Android runtime from inside the test JVM; forward the proxy.
                System.getProperty("https.proxyHost")?.let { host ->
                    test.systemProperty("robolectric.dependency.proxy.host", host)
                    test.systemProperty("robolectric.dependency.proxy.port", System.getProperty("https.proxyPort") ?: "80")
                }
                test.maxHeapSize = "3g"
                // One zone on every machine; screenshots also pin the clock itself (PinnedClock).
                test.systemProperty("user.timezone", "Asia/Shanghai")
                // The baselines are compared against, so they are an input: a cached result is
                // only reused for the very images it was compared with.
                test.inputs.dir("screenshots").withPropertyName("screenshotBaselines")
                    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE).optional()
                // -Pbrand (scripts/dev-test.sh --brand): the README's banner and icon, drawn on request only.
                if (project.hasProperty("brand")) test.systemProperty("brand", "true")
                // -PskipShots (scripts/dev-test.sh --fast): the inner loop leaves out the classes
                // that render screenshots, the slow half of the suite. They hold assertions too,
                // so the whole suite still runs before a change is done.
                if (project.hasProperty("skipShots")) {
                    test.filter {
                        excludeTestsMatching("*ShotTest")
                        excludeTestsMatching("*ScreenshotTest")
                    }
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.documentfile)
    implementation(libs.lunar)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
}
