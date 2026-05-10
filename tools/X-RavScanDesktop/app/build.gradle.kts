import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Compose Desktop — pull in the full UI bundle plus Material 3.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    implementation(compose.components.resources)

    // Coroutines — Compose Desktop already pulls coroutines-core but pin a recent version.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Serialization for seed JSON parsing in later phases.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // SQLDelight — typed Kotlin queries on top of JDBC SQLite for Phase D2.
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")

    // Phase D3 — network stack (Ktor + OkHttp engine for IPv6/HTTP2 friendliness).
    implementation("io.ktor:ktor-client-core:3.0.0")
    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
    implementation("io.ktor:ktor-client-logging:3.0.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

sqldelight {
    databases {
        create("XRavScanDb") {
            packageName.set("ai.xrav.xravscan.db")
            // Need sqlite >= 3.24 for `INSERT ... ON CONFLICT DO UPDATE`. The JDBC
            // driver bundles xerial sqlite-jdbc 3.45+, so 3.38 dialect is safe.
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.0.2")
        }
    }
}

compose.desktop {
    application {
        mainClass = "ai.xrav.xravscan.MainKt"

        // Strip unused JDK modules to keep the produced .exe small.
        // Tweak as new modules become required (e.g. java.sql when SQLDelight wires in).
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "X-RavScan"
            packageVersion = "1.0.0"
            description = "X-RavScan — IP/CDN reconnaissance for Windows."
            copyright = "© 2025 X-Rav"
            vendor = "X-Rav"

            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.naming",
                "java.net.http",
                "java.security.jgss",
                "java.sql",
                "jdk.crypto.ec",
            )

            windows {
                // Show in Add/Remove Programs and on Start menu.
                menu = true
                shortcut = true
                upgradeUuid = "0c6e9f44-3a9f-4e39-9aa1-5c9bb9f7e8a1"
                // iconFile gets picked up in Phase D5 once a real .ico ships in resources/icons/.
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
            obfuscate.set(false)
        }
    }
}
