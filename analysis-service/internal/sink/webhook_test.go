package sink

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/whyjvm/analysis-service/internal/agent"
	"github.com/whyjvm/analysis-service/internal/incident"
)

func TestWebhookPostsSlackPayload(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		got = string(b)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	laudo := agent.Laudo{
		Endpoint:             "POST /checkout",
		Tipo:                 "SLOW",
		CausaRaiz:            "pausa de GC prolongada",
		Confianca:            "alta",
		Evidencia:            []string{"GC 812ms (~19% da latencia)"},
		HipotesesDescartadas: []string{"Contencao de lock / deadlock: sem espera relevante em monitor"},
		CorrecaoSugerida:     "otimizar alocacao",
	}
	if err := NewWebhook(srv.URL).Publish(laudo); err != nil {
		t.Fatal(err)
	}

	var payload struct {
		Text string `json:"text"`
	}
	if err := json.Unmarshal([]byte(got), &payload); err != nil {
		t.Fatalf("payload nao e JSON: %v\n%s", err, got)
	}
	for _, want := range []string{"POST /checkout", "SLOW", "pausa de GC prolongada", "GC 812ms",
		"Hipoteses descartadas", "sem espera relevante em monitor", "otimizar alocacao"} {
		if !strings.Contains(payload.Text, want) {
			t.Fatalf("text do webhook sem %q:\n%s", want, payload.Text)
		}
	}
}

func TestWebhookIncludesCodeContext(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		got = string(b)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	snip := "return switch (customer.tier()) {"
	laudo := agent.Laudo{
		Endpoint:  "POST /checkout",
		Tipo:      "ERROR",
		CausaRaiz: "NPE: findById retornou nil",
		Confianca: "alta",
		CodeContext: &incident.CodeContext{
			Symbol:           "io.whyjvm.sample.checkout.CustomerService.calculateDiscount",
			File:             "CustomerService.java",
			Line:             20,
			Origin:           "SOURCE_DIR",
			Snippet:          &snip,
			SnippetStartLine: 20,
		},
	}
	if err := NewWebhook(srv.URL).Publish(laudo); err != nil {
		t.Fatal(err)
	}

	var payload struct {
		Text string `json:"text"`
	}
	if err := json.Unmarshal([]byte(got), &payload); err != nil {
		t.Fatalf("payload nao e JSON: %v\n%s", err, got)
	}
	for _, want := range []string{"Codigo do metodo suspeito", "CustomerService.java:20", "> 20 | return switch"} {
		if !strings.Contains(payload.Text, want) {
			t.Fatalf("text do webhook sem %q:\n%s", want, payload.Text)
		}
	}
}

func TestWebhookErrorsOnNon2xx(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()
	if err := NewWebhook(srv.URL).Publish(agent.Laudo{}); err == nil {
		t.Fatal("esperava erro em status 500")
	}
}

func TestFromEnvSelectsSink(t *testing.T) {
	t.Run("default e log", func(t *testing.T) {
		t.Setenv("WHYJVM_SINK", "")
		s, err := FromEnv()
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := s.(*Log); !ok {
			t.Fatalf("esperava *Log, veio %T", s)
		}
	})
	t.Run("slack sem url e erro", func(t *testing.T) {
		t.Setenv("WHYJVM_SINK", "slack")
		t.Setenv("WHYJVM_SLACK_WEBHOOK_URL", "")
		t.Setenv("WHYJVM_WEBHOOK_URL", "")
		if _, err := FromEnv(); err == nil {
			t.Fatal("esperava erro sem WHYJVM_SLACK_WEBHOOK_URL")
		}
	})
	t.Run("slack com url", func(t *testing.T) {
		t.Setenv("WHYJVM_SINK", "slack")
		t.Setenv("WHYJVM_SLACK_WEBHOOK_URL", "https://hooks.slack.com/services/x")
		s, err := FromEnv()
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := s.(*Webhook); !ok {
			t.Fatalf("esperava *Webhook, veio %T", s)
		}
	})
	t.Run("desconhecido e erro", func(t *testing.T) {
		t.Setenv("WHYJVM_SINK", "bogus")
		if _, err := FromEnv(); err == nil {
			t.Fatal("esperava erro para sink desconhecido")
		}
	})
}
