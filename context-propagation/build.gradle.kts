plugins {
    application
}

group = "com.github.yanzord"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.opentelemetry:opentelemetry-api:1.62.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.62.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

application {
    mainClass = "com.github.yanzord.contextpropagation.ContextPropagationApplication"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
