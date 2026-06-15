package bridge

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/cyberb/claude-voice/bridge/internal/bridge/models"
)

// FS is the bridge's gateway to the local filesystem. It owns every disk
// operation the bridge performs — from low-level reads up to the high-level
// session, history and directory listings — so the other components stay free
// of I/O and there is a single seam for tests to stub.
type FS struct {
	home string
}

// NewFS returns an FS backed by the real operating-system filesystem.
func NewFS() *FS {
	return &FS{home: home()}
}

// Exists reports whether path can be stat'd.
func (*FS) Exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

// ReadFile returns the contents of the named file.
func (*FS) ReadFile(path string) ([]byte, error) { return os.ReadFile(path) }

// WriteFile writes data to the named file, creating it 0600 if needed.
func (*FS) WriteFile(path string, data []byte) error { return os.WriteFile(path, data, 0o600) }

// ReadDir lists the directory entries of path, sorted by filename.
func (*FS) ReadDir(path string) ([]os.DirEntry, error) { return os.ReadDir(path) }

// Open opens the named file for reading.
func (*FS) Open(path string) (*os.File, error) { return os.Open(path) }

// TempDir creates a new temporary directory and returns its path.
func (*FS) TempDir(pattern string) (string, error) { return os.MkdirTemp("", pattern) }

// RemoveAll removes path and any children it contains.
func (*FS) RemoveAll(path string) error { return os.RemoveAll(path) }

// ListDir returns the sorted subdirectories of dir plus its parent.
func (f *FS) ListDir(dir string) (models.DirListing, error) {
	entries, err := f.ReadDir(dir)
	if err != nil {
		return models.DirListing{}, err
	}
	dirs := []string{}
	for _, e := range entries {
		if e.IsDir() {
			dirs = append(dirs, e.Name())
		}
	}
	sort.Strings(dirs)
	var parent *string
	if p := filepath.Dir(dir); p != dir {
		parent = &p
	}
	return models.DirListing{Dir: dir, Parent: parent, Dirs: dirs}, nil
}

// ListSessions returns the most recent claude sessions recorded for dir.
func (f *FS) ListSessions(dir string) []models.SessionInfo {
	proj := filepath.Join(f.home, ".claude", "projects", encodeDir(dir))
	entries, err := f.ReadDir(proj)
	if err != nil {
		return []models.SessionInfo{}
	}
	out := []models.SessionInfo{}
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".jsonl") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, models.SessionInfo{
			ID:      strings.TrimSuffix(e.Name(), ".jsonl"),
			Preview: f.sessionPreview(filepath.Join(proj, e.Name())),
			Mtime:   info.ModTime().Unix(),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Mtime > out[j].Mtime })
	if len(out) > 20 {
		out = out[:20]
	}
	return out
}

func (f *FS) sessionPreview(path string) string {
	fh, err := f.Open(path)
	if err != nil {
		return ""
	}
	defer fh.Close()
	sc := bufio.NewScanner(fh)
	sc.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
	for sc.Scan() {
		var ev models.StreamEvent
		if json.Unmarshal(sc.Bytes(), &ev) != nil || ev.Type != "user" {
			continue
		}
		if s := ev.Message.TextContent(); strings.TrimSpace(s) != "" {
			return trunc(s, 70)
		}
		for _, b := range ev.Message.Blocks() {
			if b.Type == "text" && strings.TrimSpace(b.Text) != "" {
				return trunc(b.Text, 70)
			}
		}
	}
	return ""
}

// History returns the transcript events recorded for session id under dir.
func (f *FS) History(dir, id string) []models.Event {
	path := filepath.Join(f.home, ".claude", "projects", encodeDir(dir), id+".jsonl")
	fh, err := f.Open(path)
	if err != nil {
		return []models.Event{}
	}
	defer fh.Close()
	out := []models.Event{}
	sc := bufio.NewScanner(fh)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	for sc.Scan() {
		var ev models.StreamEvent
		if json.Unmarshal(sc.Bytes(), &ev) != nil {
			continue
		}
		switch ev.Type {
		case "user":
			if s := ev.Message.TextContent(); strings.TrimSpace(s) != "" {
				out = append(out, models.Event{T: "you", Text: s})
				continue
			}
			for _, b := range ev.Message.Blocks() {
				if b.Type == "text" && strings.TrimSpace(b.Text) != "" {
					out = append(out, models.Event{T: "you", Text: b.Text})
				}
			}
		case "assistant":
			for _, b := range ev.Message.Blocks() {
				switch b.Type {
				case "text":
					t := b.Text
					if strings.TrimSpace(t) == "" {
						continue
					}
					if idx := strings.Index(t, narrateMarker); idx >= 0 {
						t = strings.TrimSpace(t[:idx])
					}
					if t != "" {
						out = append(out, models.Event{T: "reply", Text: t})
					}
				case "tool_use":
					out = append(out, models.Event{T: "action", Label: toolLabel(b.Name, b.Input)})
					if patch, file, ok := diffPatch(b.Name, b.Input); ok {
						out = append(out, models.Event{T: "diff", File: file, Patch: patch})
					}
				}
			}
		}
	}
	if len(out) > 200 {
		out = out[len(out)-200:]
	}
	return out
}

func encodeDir(d string) string {
	return strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			return r
		}
		return '-'
	}, d)
}
