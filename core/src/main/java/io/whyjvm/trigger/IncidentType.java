package io.whyjvm.trigger;

/** Natureza do incidente que disparou o gatilho. */
public enum IncidentType {
    /** Span terminou com status de erro. */
    ERROR,
    /** Span ultrapassou o baseline de latencia do endpoint. */
    SLOW
}
