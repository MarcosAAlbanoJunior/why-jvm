package llm

import (
	"errors"
	"fmt"
	"os"

	"github.com/whyjvm/analysis-service/internal/agent"
)

// FromEnv seleciona o provider por WHYJVM_LLM_PROVIDER (stub|claude|gemini). BYOK:
// a key vem do ambiente (ANTHROPIC_API_KEY / GEMINI_API_KEY); o model opcional vem
// de WHYJVM_LLM_MODEL (cai no default do provider). Default: stub (sem key).
func FromEnv() (agent.Provider, error) {
	provider := os.Getenv("WHYJVM_LLM_PROVIDER")
	model := os.Getenv("WHYJVM_LLM_MODEL")
	switch provider {
	case "", "stub":
		return agent.NewStub(), nil
	case "claude":
		key := os.Getenv("ANTHROPIC_API_KEY")
		if key == "" {
			return nil, errors.New("WHYJVM_LLM_PROVIDER=claude exige ANTHROPIC_API_KEY")
		}
		return NewClaude(key, model), nil
	case "gemini":
		key := os.Getenv("GEMINI_API_KEY")
		if key == "" {
			return nil, errors.New("WHYJVM_LLM_PROVIDER=gemini exige GEMINI_API_KEY")
		}
		return NewGemini(key, model), nil
	default:
		return nil, fmt.Errorf("WHYJVM_LLM_PROVIDER desconhecido: %q (use stub|claude|gemini)", provider)
	}
}
