plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

val otelVersion = "1.45.0"

dependencies {
    implementation(project(":core"))

    implementation("org.springframework.boot:spring-boot-starter-web")

    implementation(platform("io.opentelemetry:opentelemetry-bom:$otelVersion"))
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
