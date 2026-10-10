plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "com.github.yednamo"
version = "0.1.0"

android {
    namespace = "com.yednamo.testswap"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Google Play Install Referrer: звідки встановлено застосунок (мітка TestSwap у посиланні).
    implementation("com.android.installreferrer:installreferrer:2.2")
}

// Публікація для JitPack: com.github.yednamo:testswap-sdk-android:<тег>.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.yednamo"
                artifactId = "testswap-sdk-android"
                version = project.version.toString()
            }
        }
    }
}
