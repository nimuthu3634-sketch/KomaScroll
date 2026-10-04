import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("mihon.library")
    kotlin("android")
}

/*
 * NCNN (https://github.com/Tencent/ncnn, BSD 3-Clause) is not committed to the repository.
 * The official Android Vulkan prebuilt is downloaded once per machine into the Gradle user home
 * and verified against a pinned SHA-256 before CMake uses it.
 */
val ncnnVersion = "20260526"
val ncnnSha256 = "26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804"
val ncnnUrl = "https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion-android-vulkan.zip"
val ncnnInstallDir: File = gradle.gradleUserHomeDir.resolve("caches/komascroll/ncnn-$ncnnVersion-android-vulkan")
val ncnnRoot: File = ncnnInstallDir.resolve("ncnn-$ncnnVersion-android-vulkan")

abstract class DownloadNcnnTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val installDir: DirectoryProperty

    @TaskAction
    fun download() {
        val target = installDir.get().asFile
        val marker = target.resolve(".complete")
        if (marker.isFile && marker.readText() == sha256.get()) return

        target.deleteRecursively()
        target.mkdirs()
        val zip = File(target, "ncnn.zip")
        logger.lifecycle("Downloading ${url.get()}")
        URI(url.get()).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }

        val digest = MessageDigest.getInstance("SHA-256")
        zip.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            zip.delete()
            throw GradleException("NCNN checksum mismatch: expected ${sha256.get()}, got $actual")
        }

        val canonicalTarget = target.canonicalPath + File.separator
        ZipFile(zip).use { archive ->
            archive.entries().asSequence().forEach { entry ->
                val out = File(target, entry.name)
                check(out.canonicalPath.startsWith(canonicalTarget)) { "Bad zip entry: ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile.mkdirs()
                    archive.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        zip.delete()
        marker.writeText(sha256.get())
    }
}

val downloadNcnn = tasks.register<DownloadNcnnTask>("downloadNcnn") {
    url.set(ncnnUrl)
    sha256.set(ncnnSha256)
    installDir.set(ncnnInstallDir)
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(downloadNcnn)
    }
}

android {
    namespace = "komascroll.upscale.engine"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DNCNN_ROOT=${ncnnRoot.absolutePath.replace('\\', '/')}",
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_ARM_NEON=ON",
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}
