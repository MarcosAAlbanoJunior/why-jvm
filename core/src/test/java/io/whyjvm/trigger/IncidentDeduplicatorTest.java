package io.whyjvm.trigger;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentDeduplicatorTest {

    private static final Duration COOLDOWN = Duration.ofMinutes(10);

    @Test
    void firesOncePerCooldownThenSuppresses() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-14T12:00:00Z"));
        IncidentDeduplicator dedup = new IncidentDeduplicator(COOLDOWN, clock);

        assertTrue(dedup.shouldFire("fp"), "primeira ocorrencia deve disparar");
        assertFalse(dedup.shouldFire("fp"), "dentro do cooldown deve suprimir");
        assertFalse(dedup.shouldFire("fp"));
        assertEquals(2L, dedup.suppressedCount("fp"), "duas falhas suprimidas");
    }

    @Test
    void firesAgainAfterCooldownAndResetsCounter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-14T12:00:00Z"));
        IncidentDeduplicator dedup = new IncidentDeduplicator(COOLDOWN, clock);

        assertTrue(dedup.shouldFire("fp"));
        assertFalse(dedup.shouldFire("fp"));

        clock.advance(COOLDOWN.plusSeconds(1)); // janela expira

        assertTrue(dedup.shouldFire("fp"), "depois do cooldown deve disparar de novo");
        assertEquals(0L, dedup.suppressedCount("fp"), "contador zera na nova janela");
    }

    @Test
    void stillSuppressesAtExactCooldownBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-14T12:00:00Z"));
        IncidentDeduplicator dedup = new IncidentDeduplicator(COOLDOWN, clock);

        assertTrue(dedup.shouldFire("fp"));
        clock.advance(COOLDOWN); // exatamente no limite: ainda dentro da janela

        assertFalse(dedup.shouldFire("fp"));
    }

    @Test
    void distinctFingerprintsAreIndependent() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-14T12:00:00Z"));
        IncidentDeduplicator dedup = new IncidentDeduplicator(COOLDOWN, clock);

        assertTrue(dedup.shouldFire("a"));
        assertTrue(dedup.shouldFire("b"), "fingerprint diferente nao herda o cooldown");
        assertFalse(dedup.shouldFire("a"));
    }

    /** Relogio controlavel para exercitar o cooldown sem esperar tempo real. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration delta) {
            now = now.plus(delta);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
