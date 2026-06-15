// Command analysis-service e o lado Go do split (Fase 5): recebe os incidentes
// que o lado Java extrai do JFR, persiste e (em fases seguintes) investiga e
// despacha. B1: ingest HTTP + store.
package main

import (
	"log"
	"net/http"

	"github.com/whyjvm/analysis-service/internal/api"
	"github.com/whyjvm/analysis-service/internal/config"
	"github.com/whyjvm/analysis-service/internal/store"
)

func main() {
	cfg := config.FromEnv()

	st, err := store.NewFileStore(cfg.StoreDir)
	if err != nil {
		log.Fatalf("nao foi possivel abrir o store em %q: %v", cfg.StoreDir, err)
	}

	if cfg.IngestToken == "" {
		log.Println("AVISO: WHYJVM_INGEST_TOKEN vazio — ingest SEM auth (apenas dev).")
	}

	srv := api.NewServer(st, cfg.IngestToken)
	log.Printf("analysis-service ouvindo em %s (store=%s)", cfg.Addr, cfg.StoreDir)
	if err := http.ListenAndServe(cfg.Addr, srv); err != nil {
		log.Fatalf("servidor encerrou: %v", err)
	}
}
