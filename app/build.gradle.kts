import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins { id("com.android.application") }
val localSigning = Properties().apply {
 val config = rootProject.file(".signing/keystore.properties")
 if (config.exists()) config.inputStream().use { load(it) }
}
val releaseSigning = listOf("PITMASTER_KEYSTORE", "PITMASTER_STORE_PASSWORD", "PITMASTER_KEY_ALIAS", "PITMASTER_KEY_PASSWORD").associateWith { System.getenv(it) ?: localSigning.getProperty(it) }
android {
 namespace = "com.pitvoss.app"
 compileSdk = 36
 defaultConfig {
  applicationId = "com.pitvoss.app"
  minSdk = 26
  targetSdk = 36
  versionCode = 3
  versionName = "0.3.0-beta2"
 }
 buildFeatures { buildConfig = true }
 signingConfigs {
  if (releaseSigning.values.all { !it.isNullOrBlank() }) {
   create("upload") {
    storeFile = rootProject.file(releaseSigning.getValue("PITMASTER_KEYSTORE")!!)
    storePassword = releaseSigning.getValue("PITMASTER_STORE_PASSWORD")
    keyAlias = releaseSigning.getValue("PITMASTER_KEY_ALIAS")
    keyPassword = releaseSigning.getValue("PITMASTER_KEY_PASSWORD")
   }
  }
 }
 buildTypes {
  debug { buildConfigField("boolean", "EXPERIMENTAL_PRIMING", "true") }
  release {
   isDebuggable = false
   isMinifyEnabled = true
   isShrinkResources = true
   buildConfigField("boolean", "EXPERIMENTAL_PRIMING", "false")
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
   signingConfigs.findByName("upload")?.let { signingConfig = it }
  }
 }
 buildTypes.create("internal") {
  initWith(buildTypes.getByName("release"))
  signingConfig = signingConfigs.getByName("debug")
  matchingFallbacks += listOf("release")
  versionNameSuffix = "-internal"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 lint { abortOnError = true; checkReleaseBuilds = true }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies { testImplementation("junit:junit:4.13.2") }
