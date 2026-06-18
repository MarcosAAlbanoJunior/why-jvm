package io.whyjvm.capture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Serializacao do {@link IncidentRecord} para o JSON do contrato do split
 * ({@code schema/incident-record.v1.json}). Centraliza a configuracao do Jackson
 * — {@code Instant} como ISO-8601, nao timestamp numerico — para que o POST ao
 * servico Go e os testes usem o mesmo mapper.
 */
public final class IncidentRecordJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private IncidentRecordJson() {
    }

    /** Mapper configurado para o contrato (reutilizavel). */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(IncidentRecord record) throws JsonProcessingException {
        return MAPPER.writeValueAsString(record);
    }
}
