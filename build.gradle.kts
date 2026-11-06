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
        namespace = "lyrica"
        displayName = "Lyrica"
        author = "6O2, Philips & GCC"
        description = "Synced lyrics of the song playing on your PC, standing in the world around you or on your screen, with a now-playing widget, karaoke and staff badges."
        minecraftVersion = versions.joinToString(",")
        version = rootProject.version.toString()
    }
}

tasks.withType<net.labymod.labygradle.common.tasks.CreateReleaseJarTask>().configureEach {
    destination.set(layout.buildDirectory.file("libs/lyrica.jar"))
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