package io.whyjvm.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * App Spring Boot de teste. Existe so para gerar spans reais e provar o circuito
 * gatilho -> captura -> agente -> sink. Nao faz parte do produto (o produto e o
 * modulo :core).
 */
@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
