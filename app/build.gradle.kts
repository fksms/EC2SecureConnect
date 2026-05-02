import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val locFile = rootProject.file("local.properties")
    if (locFile.exists()) {
        locFile.inputStream().use { load(it) }
    }
}

val goBinaryName = "lib_ssm_client_exec.so"
val goModuleDir = rootProject.layout.projectDirectory.dir("go/ssm-client")
val generatedGoJniLibsDir = layout.buildDirectory.dir("generated/jniLibs/go")
val goBuildCacheDir = rootProject.layout.buildDirectory.dir("go-build-cache")
val goBinaryCommand = providers.environmentVariable("GO_BINARY")
    .getOrElse(localProperties.getProperty("go.binary") ?: "go")

val isReleaseTask =
    project.gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

android {
    namespace = "com.example.ec2secureconnect"
    compileSdk = 37
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.example.ec2secureconnect"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeBase64 = providers.environmentVariable("RELEASE_KEYSTORE_BASE64").orNull
                ?: localProperties.getProperty("release.keystore.base64")
            val storePasswordProp =
                providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                    ?: localProperties.getProperty("release.keystore.password")
            val keyAliasProp = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                ?: localProperties.getProperty("release.key.alias")
            val keyPasswordProp = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
                ?: localProperties.getProperty("release.key.password")

            val isConfigComplete =
                !storeBase64.isNullOrBlank() && !storePasswordProp.isNullOrBlank() && !keyAliasProp.isNullOrBlank() && !keyPasswordProp.isNullOrBlank()

            if (isConfigComplete) {
                val keystoreDir = rootProject.layout.projectDirectory.dir(".gradle/keystore").asFile
                keystoreDir.mkdirs()
                val keystoreFile = keystoreDir.resolve("release.jks")

                val decodedBytes =
                    Base64.getMimeDecoder().decode(storeBase64.replace("\\s".toRegex(), ""))
                keystoreFile.writeBytes(decodedBytes)

                storeFile = keystoreFile
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )

            val releaseConfig = signingConfigs.getByName("release")
            signingConfig = if (releaseConfig.storeFile != null) {
                releaseConfig
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    sourceSets.getByName("main").jniLibs.directories.add(generatedGoJniLibsDir.get().asFile.absolutePath)
}

val buildGoArm64 by tasks.registering(Exec::class) {
    val outputFile = layout.buildDirectory.file("generated/jniLibs/go/arm64-v8a/$goBinaryName")
    group = "build"
    description = "Builds the arm64-v8a ssm-client Go binary with CGO enabled."
    inputs.dir(goModuleDir)
    outputs.file(outputFile)
    workingDir(goModuleDir.asFile)

    // CGOを有効にするための設定
    val applicationExtension =
        project.extensions.getByType<com.android.build.api.dsl.ApplicationExtension>()
    val ndkDir =
        applicationExtension.ndkPath?.let { file(it) } ?: localProperties.getProperty("sdk.dir")
            ?.let { sdkDir ->
                file(sdkDir).resolve("ndk").resolve(applicationExtension.ndkVersion)
            } ?: file("missing-sdk-path")
    val minSdk = android.defaultConfig.minSdk ?: 24
    val hostOs = System.getProperty("os.name").lowercase()
    val hostTag = when {
        hostOs.contains("mac") -> "darwin-x86_64"
        hostOs.contains("linux") -> "linux-x86_64"
        hostOs.contains("windows") -> "windows-x86_64"
        else -> "linux-x86_64"
    }

    // CGOのコンパイラを外部で定義している場合はそちらを優先して利用する
    val ccPathOverride =
        providers.environmentVariable("GO_CC").getOrElse(localProperties.getProperty("go.cc") ?: "")
    val ccPath = if (ccPathOverride.isNotEmpty()) {
        file(ccPathOverride)
    } else {
        ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android$minSdk-clang")
    }

    environment("GOOS", "android")
    environment("GOARCH", "arm64")
    environment("CGO_ENABLED", "1")
    environment("CC", ccPath.absolutePath)
    environment("GOCACHE", goBuildCacheDir.get().asFile.resolve("cache").absolutePath)

    doFirst {
        if (!ccPath.exists()) {
            throw GradleException("The compiler for CGO could not be found. Please check your path: ${ccPath.absolutePath}")
        }
        outputFile.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        goBinaryCommand,
        "build",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "."
    )
}

val buildGoBinaries by tasks.registering {
    group = "build"
    description = "Builds Android Go binaries for the ssm-client bridge."
    dependsOn(buildGoArm64)
}

tasks.named("preBuild") {
    dependsOn(buildGoBinaries)
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)
    implementation(libs.gson)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}