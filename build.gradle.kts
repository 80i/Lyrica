import java.nio.file.FileSystems
import java.nio.file.Files
import net.labymod.labygradle.common.internal.labymod.addon.model.OperatingSystem

plugins {
    id("net.labymod.labygradle")
    id("net.labymod.labygradle.addon")
}

val versions = providers.gradleProperty("net.labymod.minecraft-versions").get().split(";")

group = "net.lyrica"
version = providers.environmentVariable("VERSION").getOrElse("1.0.0")

labyMod {
    defaultPackageName = "net.lyrica"

    minecraft {
        registerVersion(versions.toTypedArray()) {
            runs {
                getByName("client") {
                    // When the property is set to true, you can log in with a Minecraft account
                    // devLogin = true
                }
            }
        }
    }

    addonInfo {
        namespace = "lyrical"
        displayName = "Lyrica"
        author = "6O2, Philips & GCC"
        description = "Synced lyrics of the song playing on your PC, standing in the world around you or on your screen, with a now-playing widget, karaoke and staff badges."
        minecraftVersion = versions.joinToString(",")
        version = rootProject.version.toString()
        // It reads the song from Windows' media controls, so on other systems it finds none
        operatingSystem(OperatingSystem.WINDOWS)
    }
}

tasks.withType<net.labymod.labygradle.common.tasks.CreateReleaseJarTask>().configureEach {
    destination.set(layout.buildDirectory.file("libs/lyrica-release.jar"))

    // The launcher's addon list takes an icon from the store or from this field, and does not look for textures/icon.png
    doLast {
        val jar = destination.get().asFile.toPath()
        FileSystems.newFileSystem(jar).use { zip ->
            val info = zip.getPath("addon.json")
            val text = Files.readString(info)
            check(text.startsWith("{")) { "addon.json does not start with an object" }
            Files.writeString(info, "{\"icon\":\"assets/lyrical/textures/icon.png\"," + text.substring(1))
        }
    }
}

subprojects {
    plugins.apply("net.labymod.labygradle")
    plugins.apply("net.labymod.labygradle.addon")

    group = rootProject.group
    version = rootProject.version

    extensions.findByType(JavaPluginExtension::class.java)?.apply {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}