plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.plainnotes.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.plainnotes.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
        val updateRepository = providers.gradleProperty("updateRepository").orElse("billeequa/free_notes").get()
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(updateRepository))
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (System.getenv("JNOTES_KEYSTORE") != null) {
            create("distribution") {
                storeFile = file(System.getenv("JNOTES_KEYSTORE"))
                storePassword = System.getenv("JNOTES_STORE_PASSWORD")
                keyAlias = System.getenv("JNOTES_KEY_ALIAS")
                keyPassword = System.getenv("JNOTES_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("distribution")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.14.2")

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}



