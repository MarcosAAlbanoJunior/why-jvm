package llm

import "testing"

func TestFromEnvDefaultsToStub(t *testing.T) {
	t.Setenv("WHYJVM_LLM_PROVIDER", "")
	p, err := FromEnv()
	if err != nil || p.Name() != "stub" {
		t.Fatalf("esperava stub, veio %v / %v", p, err)
	}
}

func TestFromEnvClaudeRequiresKey(t *testing.T) {
	t.Setenv("WHYJVM_LLM_PROVIDER", "claude")
	t.Setenv("ANTHROPIC_API_KEY", "")
	if _, err := FromEnv(); err == nil {
		t.Fatal("esperava erro sem ANTHROPIC_API_KEY")
	}

	t.Setenv("ANTHROPIC_API_KEY", "k")
	p, err := FromEnv()
	if err != nil || p.Name() != "claude" {
		t.Fatalf("esperava claude, veio %v / %v", p, err)
	}
}

func TestFromEnvGeminiRequiresKey(t *testing.T) {
	t.Setenv("WHYJVM_LLM_PROVIDER", "gemini")
	t.Setenv("GEMINI_API_KEY", "")
	if _, err := FromEnv(); err == nil {
		t.Fatal("esperava erro sem GEMINI_API_KEY")
	}

	t.Setenv("GEMINI_API_KEY", "k")
	p, err := FromEnv()
	if err != nil || p.Name() != "gemini" {
		t.Fatalf("esperava gemini, veio %v / %v", p, err)
	}
}

func TestFromEnvUnknownProvider(t *testing.T) {
	t.Setenv("WHYJVM_LLM_PROVIDER", "bogus")
	if _, err := FromEnv(); err == nil {
		t.Fatal("esperava erro para provider desconhecido")
	}
}
