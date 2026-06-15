// Package incident modela o IncidentRecord do contrato do split. As structs
// batem 1:1 com schema/incident-record.v1.json (chaves json = nomes do schema);
// campos anulaveis sao ponteiros. O Go NUNCA parseia JFR — so consome este JSON.
package incident

import (
	"errors"
	"fmt"
)

// SchemaVersion e a unica versao do contrato que este servico aceita hoje.
const SchemaVersion = 1

// Record e o pacote de evidencia de um incidente, ja com os agregados extraidos
// pelo lado Java.
type Record struct {
	SchemaVersion   int            `json:"schemaVersion"`
	IncidentID      string         `json:"incidentId"`
	CapturedAt      string         `json:"capturedAt"` // ISO-8601; string para round-trip fiel
	Endpoint        string         `json:"endpoint"`
	Type            string         `json:"type"` // ERROR | SLOW
	Fingerprint     string         `json:"fingerprint"`
	ThreadName      *string        `json:"threadName"`
	DurationMs      int64          `json:"durationMs"`
	OccurrenceCount int            `json:"occurrenceCount"`
	Exception       *Exception     `json:"exception"`
	Baseline        *Baseline      `json:"baseline"`
	TriageSignals   *TriageSignals `json:"triageSignals"`
	Dimensions      Dimensions     `json:"dimensions"`
	JvmContext      *JvmContext    `json:"jvmContext"`
	JfrArtifactURI  *string        `json:"jfrArtifactUri"`
}

// Exception traz os detalhes da exception do span (quando type == ERROR).
type Exception struct {
	Type       string  `json:"type"`
	Message    *string `json:"message"`
	StackTrace *string `json:"stackTrace"`
}

// Baseline e o comportamento normal do endpoint, para a triagem comparar.
type Baseline struct {
	P99Ms               float64 `json:"p99Ms"`
	SampleCount         int     `json:"sampleCount"`
	ThresholdMultiplier float64 `json:"thresholdMultiplier"`
}

// TriageSignals sao os numeros headline de cada dimensao (passada barata).
type TriageSignals struct {
	GcCount          int   `json:"gcCount"`
	LongestGcPauseMs int64 `json:"longestGcPauseMs"`
	TotalGcPauseMs   int64 `json:"totalGcPauseMs"`
	TotalLockWaitMs  int64 `json:"totalLockWaitMs"`
	TotalAllocBytes  int64 `json:"totalAllocBytes"`
}

// Dimensions agrupa os agregados por dimensao. Cada um pode ser nil/ausente.
type Dimensions struct {
	GcActivity         *GcActivity         `json:"gcActivity"`
	AllocationHotspots *AllocationHotspots `json:"allocationHotspots"`
	LockContention     *LockContention     `json:"lockContention"`
	ThreadActivity     *ThreadActivity     `json:"threadActivity"`
	SlowTraces         []SlowTrace         `json:"slowTraces"`
}

// GcActivity sao as pausas de GC na janela.
type GcActivity struct {
	Count        int       `json:"count"`
	TotalPauseMs int64     `json:"totalPauseMs"`
	Pauses       []GcPause `json:"pauses"`
}

// GcPause e uma coleta de GC.
type GcPause struct {
	Name           string `json:"name"`
	Cause          string `json:"cause"`
	LongestPauseMs int64  `json:"longestPauseMs"`
	SumPausesMs    int64  `json:"sumPausesMs"`
}

// AllocationHotspots sao os top call sites por bytes alocados (amostrado).
type AllocationHotspots struct {
	TotalSampledBytes int64       `json:"totalSampledBytes"`
	SampleCount       int         `json:"sampleCount"`
	TopSites          []AllocSite `json:"topSites"`
}

// AllocSite e um call site e quanto alocou.
type AllocSite struct {
	Site  string  `json:"site"`
	Bytes int64   `json:"bytes"`
	Pct   float64 `json:"pct"`
}

// LockContention sao os call sites por tempo de espera em monitor.
type LockContention struct {
	TotalWaitMs int64      `json:"totalWaitMs"`
	EventCount  int        `json:"eventCount"`
	TopSites    []LockSite `json:"topSites"`
}

// LockSite e um call site que esperou um monitor.
type LockSite struct {
	Site         string `json:"site"`
	MonitorClass string `json:"monitorClass"`
	WaitMs       int64  `json:"waitMs"`
}

// ThreadActivity e o que a thread do request fez na janela.
type ThreadActivity struct {
	Thread     string  `json:"thread"`
	SleepMs    int64   `json:"sleepMs"`
	SleepSite  *string `json:"sleepSite"`
	IoMs       int64   `json:"ioMs"`
	LockMs     int64   `json:"lockMs"`
	ParkMs     int64   `json:"parkMs"`
	CpuSamples int     `json:"cpuSamples"`
}

// SlowTrace e um span lento do trace (pendente no lado Java; por ora ausente).
type SlowTrace struct {
	Span    string `json:"span"`
	SelfMs  int64  `json:"selfMs"`
	TotalMs int64  `json:"totalMs"`
}

// JvmContext sao os metadados da JVM no momento do incidente.
type JvmContext struct {
	HeapUsedMb  int64  `json:"heapUsedMb"`
	HeapMaxMb   int64  `json:"heapMaxMb"`
	GcName      string `json:"gcName"`
	ThreadCount int    `json:"threadCount"`
}

// Validate checa o minimo para aceitar o incidente no ingest. A validacao
// completa de schema fica para um passo posterior (lib externa); aqui sao os
// invariantes que o servico precisa para rotear e indexar.
func (r *Record) Validate() error {
	if r.SchemaVersion != SchemaVersion {
		return fmt.Errorf("schemaVersion %d nao suportado (esperado %d)", r.SchemaVersion, SchemaVersion)
	}
	if r.IncidentID == "" {
		return errors.New("incidentId vazio")
	}
	if r.Type != "ERROR" && r.Type != "SLOW" {
		return fmt.Errorf("type invalido: %q (esperado ERROR ou SLOW)", r.Type)
	}
	if r.Endpoint == "" {
		return errors.New("endpoint vazio")
	}
	if r.Fingerprint == "" {
		return errors.New("fingerprint vazio")
	}
	return nil
}
