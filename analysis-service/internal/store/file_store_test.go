package store

import (
	"bytes"
	"testing"
)

func TestSaveFindIdempotent(t *testing.T) {
	s, err := NewFileStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}

	// id com ':' (timestamp ISO) — exercita o escape do nome de arquivo no Windows.
	id := "2026-06-15T14:03:07Z-checkout-a1b2c3"
	first := []byte(`{"incidentId":"x","schemaVersion":1}`)
	if err := s.Save(id, first); err != nil {
		t.Fatal(err)
	}

	got, found, err := s.Find(id)
	if err != nil || !found {
		t.Fatalf("find: found=%v err=%v", found, err)
	}
	if !bytes.Equal(got, first) {
		t.Fatalf("conteudo diferente do gravado: %s", got)
	}

	if has, _ := s.Has(id); !has {
		t.Fatal("Has deveria ser true apos Save")
	}

	// idempotente: segunda gravacao (bytes diferentes) NAO sobrescreve.
	if err := s.Save(id, []byte(`{"schemaVersion":1,"v":2}`)); err != nil {
		t.Fatal(err)
	}
	got2, _, _ := s.Find(id)
	if !bytes.Equal(got2, first) {
		t.Fatalf("idempotencia quebrada: esperava a 1a gravacao, veio %s", got2)
	}

	// id desconhecido nao e encontrado.
	if _, found, _ := s.Find("inexistente"); found {
		t.Fatal("nao deveria encontrar id desconhecido")
	}
}
