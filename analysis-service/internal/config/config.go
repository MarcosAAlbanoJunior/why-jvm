// Package config le a configuracao do servico do ambiente (BYO/12-factor).
package config

import "os"

// Config sao os parametros de execucao do servico.
type Config struct {
	Addr            string // endereco de escuta, ex.: ":8080"
	IngestToken     string // bearer exigido no POST /v1/incidents; vazio = sem auth (dev)
	StoreDir        string // diretorio do store em disco
	AutoInvestigate bool   // modo autonomo: investiga ao receber o incidente
}

// FromEnv monta a config a partir das variaveis de ambiente, com defaults de dev.
func FromEnv() Config {
	return Config{
		Addr:            getenv("WHYJVM_ADDR", ":8080"),
		IngestToken:     os.Getenv("WHYJVM_INGEST_TOKEN"),
		StoreDir:        getenv("WHYJVM_STORE_DIR", "incidents-store"),
		AutoInvestigate: getenvBool("WHYJVM_AUTO_INVESTIGATE", true),
	}
}

// getenvBool: "false"/"0"/"no" desligam; qualquer outro valor (ou ausente) cai no
// fallback. Default do servico e investigar automaticamente ao receber incidente.
func getenvBool(key string, fallback bool) bool {
	switch os.Getenv(key) {
	case "false", "0", "no":
		return false
	case "true", "1", "yes":
		return true
	default:
		return fallback
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
