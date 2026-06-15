plugins {
    java
    `java-library`
}

dependencies {
    // O nucleo (LlmProvider, Message, Tool) faz parte da API publica do modulo.
    api(project(":core"))

    // LangChain4j como camada de provider. Fica AQUI, fora do core, para o core
    // seguir "JAR puro". Trocar de provider = trocar a dependencia + a env var,
    // sem mexer no adapter.
    implementation(platform("dev.langchain4j:langchain4j-bom:1.0.1"))
    implementation("dev.langchain4j:langchain4j-core")
    implementation("dev.langchain4j:langchain4j-google-ai-gemini")

    // Para serializar/desserializar os argumentos de tool (JSON).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}
