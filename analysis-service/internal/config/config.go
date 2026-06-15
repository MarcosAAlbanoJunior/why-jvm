// Package config le a configuracao do servico do ambiente (BYO/12-factor).
package config

import "os"

// Config sao os parametros de execucao do servico.
type Config struct {
	Addr        string // endereco de escuta, ex.: ":8080"
	IngestToken string // bearer exigido no POST /v1/incidents; vazio = sem auth (dev)
	StoreDir    string // diretorio do store em disco
}

// FromEnv monta a config a partir das variaveis de ambiente, com defaults de dev.
func FromEnv() Config {
	return Config{
		Addr:        getenv("WHYJVM_ADDR", ":8080"),
		IngestToken: os.Getenv("WHYJVM_INGEST_TOKEN"),
		StoreDir:    getenv("WHYJVM_STORE_DIR", "incidents-store"),
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
