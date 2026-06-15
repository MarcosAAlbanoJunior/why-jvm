plugins {
    java
    `java-library`
}

dependencies {
    // O nucleo (Sink, Laudo) faz parte da API publica deste modulo.
    api(project(":core"))

    // SMTP via Jakarta Mail (impl Angus). Fica AQUI, fora do core, para manter
    // o core "JAR puro" sem dependencia de transporte.
    implementation("jakarta.mail:jakarta.mail-api:2.1.3")
    implementation("org.eclipse.angus:angus-mail:2.0.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}
