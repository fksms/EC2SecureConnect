import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val goBinaryName = "lib_ssm_client_exec.so"
val goModuleDir = rootProject.layout.projectDirectory.dir("go/ssm-client")
val generatedGoJniLibsDir = layout.buildDirectory.dir("generated/jniLibs/go")
val goBuildCacheDir = rootProject.layout.buildDirectory.dir("go-build-cache")
val goBinaryCommand =
    providers.gradleProperty("goBinary").orElse(providers.environmentVariable("GO_BINARY"))
        .orElse("go")

android {
    namespace = "com.example.ec2secureconnect"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.example.ec2secureconnect"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
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
    sourceSets.getByName("main").jniLibs.srcDir(generatedGoJniLibsDir.get().asFile)
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
    val ndkDir = applicationExtension.ndkPath?.let { file(it) }
        ?: project.rootProject.file("local.properties").let { propFile ->
            val props = Properties()
            if (propFile.exists()) {
                propFile.inputStream().use { props.load(it) }
            }
            val sdkDir = props.getProperty("sdk.dir")
            val ndkVersion = applicationExtension.ndkVersion
            if (sdkDir != null) {
                file(sdkDir).resolve("ndk").resolve(ndkVersion)
            } else {
                file("missing-sdk-path")
            }
        }
    val minSdk = android.defaultConfig.minSdk ?: 24
    val hostOs = System.getProperty("os.name").lowercase()
    val hostTag = when {
        hostOs.contains("mac") -> "darwin-x86_64"
        hostOs.contains("linux") -> "linux-x86_64"
        hostOs.contains("windows") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
    val ccPath =
        ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android$minSdk-clang")

    environment("GOOS", "android")
    environment("GOARCH", "arm64")
    environment("CGO_ENABLED", "1")
    environment("CC", ccPath.absolutePath)
    environment("GOCACHE", goBuildCacheDir.get().asFile.resolve("cache").absolutePath)

    doFirst {
        if (!ndkDir.exists()) {
            throw GradleException("NDKが見つかりません。Android StudioのSDK ManagerからNDKをインストールしてください。")
        }
        outputFile.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        goBinaryCommand.get(),
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