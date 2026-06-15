package store

import (
	"errors"
	"net/url"
	"os"
	"path/filepath"
)

// FileStore persiste cada incidente como um arquivo .json no diretorio. O nome
// do arquivo e url.QueryEscape(incidentId) porque o incidentId contem ':'
// (timestamp ISO), invalido em nomes de arquivo no Windows; o escape e
// reversivel e livre de colisao.
type FileStore struct {
	dir string
}

// NewFileStore cria (se preciso) o diretorio do store.
func NewFileStore(dir string) (*FileStore, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	return &FileStore{dir: dir}, nil
}

func (s *FileStore) path(incidentID string) string {
	return filepath.Join(s.dir, url.QueryEscape(incidentID)+".json")
}

// Save grava o JSON cru de forma idempotente e atomica (temp + rename). Se o id
// ja existe, mantem a primeira gravacao.
func (s *FileStore) Save(incidentID string, raw []byte) error {
	p := s.path(incidentID)
	if _, err := os.Stat(p); err == nil {
		return nil // ja existe: idempotente, primeira gravacao prevalece
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return err
	}
	if err := os.Rename(tmp, p); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

// Find le o JSON cru do incidente.
func (s *FileStore) Find(incidentID string) ([]byte, bool, error) {
	raw, err := os.ReadFile(s.path(incidentID))
	if errors.Is(err, os.ErrNotExist) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	return raw, true, nil
}

// Has informa se o incidente ja existe no store.
func (s *FileStore) Has(incidentID string) (bool, error) {
	_, err := os.Stat(s.path(incidentID))
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}
