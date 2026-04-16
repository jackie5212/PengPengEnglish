import org.gradle.api.tasks.bundling.Zip
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

val pengpengOnlyArm64: Boolean =
    rootProject.providers.gradleProperty("pengpeng.onlyArm64").orNull == "true"

/** Built by upstream CMake (android/jni) into the espeak-ng checkout. */
val espeakGeneratedDataDir: File =
    rootProject.file("UniApp/third_party/espeak-ng/android/build/generated/espeak-ng-data")

val generatedEspeakResRoot = layout.buildDirectory.dir("generated-espeak-res")
val generatedRawDir = layout.buildDirectory.dir("generated-espeak-res/raw")

val zipEspeakData = tasks.register<Zip>("zipEspeakData") {
    description = "Zip espeak-ng-data for packaging into AAR/APK (res/raw)"
    from(espeakGeneratedDataDir) {
        into("espeak-ng-data")
    }
    archiveFileName.set("espeakdata.zip")
    destinationDirectory.set(generatedRawDir)
    isZip64 = true

    doFirst {
        require(espeakGeneratedDataDir.resolve("en_dict").exists()) {
            "缺少 eSpeak NG 语音数据目录：\n${espeakGeneratedDataDir.absolutePath}\n请先执行 externalNativeBuildDebug"
        }
    }
}

val writeEspeakDataVersion = tasks.register("writeEspeakDataVersion") {
    dependsOn(zipEspeakData)
    doLast {
        val zipFile = zipEspeakData.get().archiveFile.get().asFile
        val verFile = generatedRawDir.get().asFile.resolve("espeakdata_version")
        verFile.parentFile.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256").digest(zipFile.readBytes())
        val hex = digest.joinToString("") { b -> "%02x".format(b) }
        verFile.writeText(hex)
    }
}

val prepareEspeakBundledRes = tasks.register("prepareEspeakBundledRes") {
    group = "build"
    description = "Prepare generated eSpeak raw resources"
    dependsOn(writeEspeakDataVersion)
    outputs.dir(generatedEspeakResRoot)
}

android {
    namespace = "com.reecedunn.espeak"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        if (pengpengOnlyArm64) {
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DUSE_ASYNC:BOOL=OFF",
                    "-DUSE_MBROLA:BOOL=OFF",
                    "-DESPEAK_ALLOW_FETCHCONTENT_SONIC:BOOL=OFF",
                )
                targets += listOf("ttsespeak", "espeak-data")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets.named("main") {
        res.srcDir("build/generated-espeak-res")
    }
}

@Suppress("UnstableApiUsage")
androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(prepareEspeakBundledRes) { _ ->
            objects.directoryProperty().apply { set(generatedEspeakResRoot) }
        }
    }
}

afterEvaluate {
    zipEspeakData.configure {
        dependsOn(tasks.named("externalNativeBuildDebug"))
    }
    tasks.named("mergeDebugResources").configure { dependsOn(prepareEspeakBundledRes) }
}
