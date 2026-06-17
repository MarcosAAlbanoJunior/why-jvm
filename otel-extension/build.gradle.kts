plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

val otelVersion = "1.45.0"

dependencies {
    // Traz captura + forward + LoggingSink + StubLlmProvider + Jackson. A API/SDK do
    // OTel e EXCLUIDA: o agente OpenTelemetry a prove em runtime (classloader isolado),
    // entao nao deve entrar no shaded jar.
    implementation(project(":core")) {
        exclude(group = "io.opentelemetry")
    }

    // Provido pelo agente OTel em runtime — so para compilar (SPI + tipos do SDK).
    compileOnly(platform("io.opentelemetry:opentelemetry-bom:$otelVersion"))
    compileOnly("io.opentelemetry:opentelemetry-sdk")
    compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")

    // Nos testes o OTel precisa existir de verdade (sem agente): traz o SDK + autoconfigure.
    testImplementation(platform("io.opentelemetry:opentelemetry-bom:$otelVersion"))
    testImplementation("io.opentelemetry:opentelemetry-sdk")
    testImplementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.shadowJar {
    archiveBaseName.set("why-jvm-otel-extension")
    archiveClassifier.set("all")
    // Evita conflito de versao de Jackson com o app host.
    relocate("com.fasterxml.jackson", "io.whyjvm.shaded.jackson")
    // Renomeia/relocaliza os META-INF/services (SPI) conforme o relocate — o do OTel
    // (nao relocado) fica intacto; os do Jackson passam a apontar para o pacote shaded.
    mergeServiceFiles()
}
