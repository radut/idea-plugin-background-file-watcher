import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.radut.plugin"
version = "1.1.1"

// Platform we compile against. Keep this at the OLDEST supported IDE so we can never
// accidentally call an API that does not exist on it; forward compatibility is proven
// by the `verifyPlugin` task below instead of by an `until-build` upper bound.
val platformVersion = "2023.3.8"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(platformVersion)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
            // No until-build: the plugin stays compatible with every future IDE release
            // instead of being disabled the moment a new major version ships.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // Verify against the latest release of every IDEA major version from our
            // since-build up to the newest one available. No upper bound is pinned here
            // either, so new majors are picked up automatically as they ship.
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaCommunity)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "233"
                untilBuild = "252.*"
            }
            select {
                // 2025.3 unified the IDEA distributions: from that build on there is no
                // separate `ideaIC` download, only the single `IntellijIdea` one.
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "253"
                untilBuild = provider { null }
            }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks {
    withType<JavaCompile> {
        options.release = 17
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
    }
}

// Smoke-test the plugin on an IDE newer than the one we compile against, e.g.
//   ./gradlew runIdeOn -PideVersion=2026.1.5 -PideProject=/path/to/some/project
// Handy because `runIde` always uses `platformVersion`, our compatibility floor.
intellijPlatformTesting.runIde.register("runIdeOn") {
    type = IntelliJPlatformType.IntellijIdea
    version = providers.gradleProperty("ideVersion").orElse("2026.1.5")
    task {
        providers.gradleProperty("ideProject").orNull?.let { args = listOf(it) }
    }
}
