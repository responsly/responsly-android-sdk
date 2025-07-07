import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    signing
}

group = "com.responsly"
version = "0.6.5"

android {
    namespace = "com.responsly.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        // The SDK itself builds against API 36; apps only need what androidx.webkit asks for.
        aarMetadata {
            minCompileSdk = 33
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

    testOptions {
        unitTests.all { test ->
            // Tests assert that Responsly.SDK_VERSION matches the published version.
            test.systemProperty("responsly.sdk.version", project.version.toString())
        }
    }

    sourceSets {
        // PublicApiTest reads the not-shown reasons from the protocol document.
        getByName("test").resources.directories.add(rootProject.file("docs").path)
    }

    lint {
        abortOnError = true
        // Versions are pinned on purpose: androidx.webkit 1.16+ needs minSdk 24, and the other
        // libraries are kept at versions that apps on older toolchains can still consume.
        disable += setOf("GradleDependency", "NewerVersionAvailable")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin {
    explicitApi()
    // The standard library the SDK asks its users for; kept low on purpose.
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        // Readable by apps and wrappers that are still on an older Kotlin compiler.
        languageVersion = KotlinVersion.KOTLIN_2_0
        apiVersion = KotlinVersion.KOTLIN_2_0
        // Default methods of ResponslyListener are real Java default methods.
        freeCompilerArgs.add("-Xjvm-default=all")
        // Plain StringBuilder concatenation: nothing for an app's dexer to desugar.
        freeCompilerArgs.add("-Xstring-concat=inline")
    }
}

dependencies {
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    // The org.json classes in android.jar are stubs; unit tests need the real thing.
    testImplementation(libs.org.json)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.responsly"
            artifactId = "sdk"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name = "Responsly Android SDK"
                description = "In-app surveys for Android apps: targeted on the device, shown in a native container."
                url = "https://github.com/responsly/responsly-android-sdk"
                inceptionYear = "2026"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "responsly"
                        name = "Responsly"
                        url = "https://responsly.com"
                    }
                }
                scm {
                    url = "https://github.com/responsly/responsly-android-sdk"
                    connection = "scm:git:https://github.com/responsly/responsly-android-sdk.git"
                    developerConnection = "scm:git:ssh://git@github.com/responsly/responsly-android-sdk.git"
                }
                issueManagement {
                    system = "GitHub"
                    url = "https://github.com/responsly/responsly-android-sdk/issues"
                }
            }
        }
    }
}

signing {
    // Releases are signed in CI; local builds and publishToMavenLocal work without keys.
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
