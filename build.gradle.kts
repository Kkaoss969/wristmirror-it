plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.wrapper {
    gradleVersion = "9.4.1"
    distributionType = Wrapper.DistributionType.BIN
    distributionUrl = "https://downloads.gradle.org/distributions/gradle-9.4.1-bin.zip"
    distributionSha256Sum = "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"
}
