// Command analysis-service e o lado Go do split (Fase 5): recebe os incidentes
// que o lado Java extrai do JFR, persiste e (em fases seguintes) investiga e
// despacha. B1: ingest HTTP + store.
package main

import (
	"cmp"
	"log"
	"net/http"
	"os"

	"github.com/whyjvm/analysis-service/internal/api"
	"github.com/whyjvm/analysis-service/internal/config"
	"github.com/whyjvm/analysis-service/internal/llm"
	"github.com/whyjvm/analysis-service/internal/sink"
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

	provider, err := llm.FromEnv()
	if err != nil {
		log.Fatalf("provider LLM: %v", err)
	}

	snk, err := sink.FromEnv()
	if err != nil {
		log.Fatalf("sink: %v", err)
	}

	srv := api.NewServer(st, api.Options{
		Token:           cfg.IngestToken,
		Provider:        provider,
		Sink:            snk,
		AutoInvestigate: cfg.AutoInvestigate,
	})
	log.Printf("analysis-service ouvindo em %s (store=%s, provider=%s, sink=%s, auto_investigate=%v)",
		cfg.Addr, cfg.StoreDir, provider.Name(), cmp.Or(os.Getenv("WHYJVM_SINK"), "log"), cfg.AutoInvestigate)
	if err := http.ListenAndServe(cfg.Addr, srv); err != nil {
		log.Fatalf("servidor encerrou: %v", err)
	}
}
