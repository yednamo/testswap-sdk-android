plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "com.github.yednamo"
version = "0.2.1"

android {
    namespace = "com.yednamo.testswap"
    compileSdk = 36

    defaultConfig {
        // Android 8.0 (API 26): нижче 7.1.1 системне сховище не довіряє ланцюжкам Let's Encrypt
        // (немає ISRG Root X1), тож testswap.yednamo.com недоступний; правило сайту для застосунків
        // з SDK — Android 8+. Сам Play Integrity API (бібліотека 1.5+) потребує лише API 23.
        minSdk = 26
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
    // Google Play Integrity API: підтвердження встановлення з Google Play і сертифікованого пристрою.
    implementation("com.google.android.play:integrity:1.6.0")
    // Юніт-тести правил рішень (Policy) на JVM, без Android.
    testImplementation("junit:junit:4.13.2")
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
