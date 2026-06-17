package incident

import (
	"strings"
	"testing"
)

func TestCodeContextRenderNumbersAndMarksFrameLine(t *testing.T) {
	snip := strings.Join([]string{
		"Customer customer = repository.findById(customerId);",
		"return switch (customer.tier()) {",
		`  case "GOLD" -> 0.20;`,
	}, "\n")
	cc := &CodeContext{
		Symbol:           "io.app.checkout.CustomerService.calculateDiscount",
		File:             "CustomerService.java",
		Line:             20,
		Origin:           "SOURCE_DIR",
		Snippet:          &snip,
		SnippetStartLine: 19,
	}

	out := cc.Render()

	for _, want := range []string{
		"CustomerService.java:20",
		"fonte: SOURCE_DIR",
		" 19 | Customer customer = repository.findById(customerId);",
		"> 20 | return switch (customer.tier()) {", // marcador na linha do frame
		` 21 |   case "GOLD" -> 0.20;`,
	} {
		if !strings.Contains(out, want) {
			t.Fatalf("render sem %q:\n%s", want, out)
		}
	}
}

func TestCodeContextRenderUnavailableIsHonestOneLiner(t *testing.T) {
	cc := &CodeContext{
		Symbol: "io.app.checkout.CustomerService.calculateDiscount",
		File:   "CustomerService.java",
		Line:   20,
		Origin: "UNAVAILABLE",
	}

	want := "io.app.checkout.CustomerService.calculateDiscount (CustomerService.java:20)" +
		" — fonte indisponivel em runtime"
	if got := cc.Render(); got != want {
		t.Fatalf("esperava %q, veio %q", want, got)
	}
}
