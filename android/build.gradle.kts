plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
    "编译需要 JDK 21，当前是 ${JavaVersion.current()}"
}
