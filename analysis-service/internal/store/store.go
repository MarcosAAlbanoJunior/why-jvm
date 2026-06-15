// Package store persiste os incidentes recebidos. Guarda os bytes JSON crus
// (lossless), indexados por incidentId. Disco no B1; Postgres depois.
package store

// Store e o armazenamento durable e idempotente de incidentes.
type Store interface {
	// Save persiste o JSON cru sob o incidentId. Idempotente: se o id ja existe,
	// e no-op (a primeira gravacao prevalece).
	Save(incidentID string, raw []byte) error
	// Find devolve o JSON cru do incidente; found=false se nao existir.
	Find(incidentID string) (raw []byte, found bool, err error)
	// Has informa se o incidente ja foi persistido.
	Has(incidentID string) (bool, error)
}
