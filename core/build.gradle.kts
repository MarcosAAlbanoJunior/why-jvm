plugins {
    java
    `java-library`
}

val otelVersion = "1.45.0"

dependencies {
    // OpenTelemetry: gatilho (SpanProcessor) e modelo de spans.
    api(platform("io.opentelemetry:opentelemetry-bom:$otelVersion"))
    api("io.opentelemetry:opentelemetry-api")
    api("io.opentelemetry:opentelemetry-sdk")

    // JSON para serializar o laudo estruturado e payloads de LLM/sink.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    // jsr310: serializa Instant (capturedAt) como ISO-8601 no contrato do split.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    // Nota: a captura JFR usa o modulo jdk.jfr, embutido no JDK (sem dependencia).
    // Nota: o SDK MCP (io.modelcontextprotocol.sdk:mcp) entra so na Fase 5,
    //       quando o servidor MCP for separado por transporte HTTP. No v1 as
    //       tools sao chamadas em processo pelo proprio agente.

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    // Valida o IncidentRecord serializado contra schema/incident-record.v1.json
    // (fecha o loop produtor <-> contrato em CI).
    testImplementation("com.networknt:json-schema-validator:1.5.4")
}
