package io.whyjvm.capture;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Store em memoria para o v1. O snapshot JFR ja esta em disco; aqui guardamos
 * apenas os metadados do incidente, indexados por id.
 *
 * <p>TODO Fase 5: trocar por Postgres para sobreviver a restart e atender
 * varias JVMs a partir de um cerebro central.
 */
public final class InMemoryIncidentStore implements IncidentStore {

    private final ConcurrentMap<String, IncidentRecord> records = new ConcurrentHashMap<>();

    @Override
    public void save(IncidentRecord record) {
        records.put(record.incidentId(), record);
    }

    @Override
    public Optional<IncidentRecord> find(String incidentId) {
        return Optional.ofNullable(records.get(incidentId));
    }
}
