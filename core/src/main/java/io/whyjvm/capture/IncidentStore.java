package io.whyjvm.capture;

import java.util.Optional;

/**
 * Armazenamento duravel dos incidentes. Diretorio no v1, Postgres quando
 * escalar. O agente investiga o registro congelado, de forma assincrona.
 */
public interface IncidentStore {

    void save(IncidentRecord record);

    Optional<IncidentRecord> find(String incidentId);
}
