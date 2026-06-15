package bridge

import (
	"encoding/json"
	"io"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/cyberb/claude-voice/bridge/internal/bridge/models"
)

// Server adapts the pure Handlers business logic to HTTP routes. All knowledge
// of net/http — request parsing, status codes, response encoding — lives here.
type Server struct {
	addr string
	h    *Handlers
	fs   *FS
}

// NewServer injects the Handlers and FS implementations behind the routes.
func NewServer(addr string, h *Handlers, fs *FS) *Server {
	return &Server{addr: addr, h: h, fs: fs}
}

// Handler builds the route table mapping each path to a Server method.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.health)
	mux.HandleFunc("/agents", s.agents)
	mux.HandleFunc("/agents/", s.agentByID)
	mux.HandleFunc("/ls", s.ls)
	mux.HandleFunc("/sessions", s.sessions)
	mux.HandleFunc("/history", s.history)
	mux.HandleFunc("/stt", s.stt)
	mux.HandleFunc("/chat", s.chat)
	mux.HandleFunc("/tts", s.tts)
	mux.HandleFunc("/voices", s.voices)
	return mux
}

// ListenAndServe starts the HTTP server on the configured address.
func (s *Server) ListenAndServe() error {
	return http.ListenAndServe(s.addr, s.Handler())
}

func writeText(w http.ResponseWriter, code int, body string) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(code)
	io.WriteString(w, body)
}

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func mustJSON(v interface{}) []byte {
	b, _ := json.Marshal(v)
	return b
}

func normalizeDir(dir string) string {
	dir = expand(strings.TrimSpace(dir))
	if abs, err := filepath.Abs(dir); err == nil {
		dir = abs
	}
	return dir
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	writeText(w, 200, "ok")
}

func (s *Server) voices(w http.ResponseWriter, r *http.Request) {
	if !s.fs.PiperEnabled() {
		writeJSON(w, 200, []string{})
		return
	}
	writeJSON(w, 200, s.fs.ListVoices())
}

func (s *Server) tts(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeText(w, 405, "method not allowed")
		return
	}
	if !s.fs.PiperEnabled() {
		writeText(w, 501, "piper not configured")
		return
	}
	var p models.TtsReq
	json.NewDecoder(r.Body).Decode(&p)
	if strings.TrimSpace(p.Text) == "" {
		writeText(w, 400, "empty text")
		return
	}
	wav, err := s.h.synth(p.Text, p.Voice)
	if err != nil {
		writeText(w, 500, "tts failed")
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	w.Header().Set("Content-Length", strconv.Itoa(len(wav)))
	w.WriteHeader(200)
	w.Write(wav)
}

func (s *Server) agents(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, 200, s.h.agentList())
	case http.MethodPost:
		var p models.AgentReq
		json.NewDecoder(r.Body).Decode(&p)
		if strings.TrimSpace(p.Dir) == "" {
			writeText(w, 400, "missing dir")
			return
		}
		aid := s.h.AddAgent(p.Dir)
		if p.Session != "" {
			s.h.SetSession(aid, p.Session)
		}
		writeJSON(w, 200, s.h.agentList())
	default:
		writeText(w, 405, "method not allowed")
	}
}

func (s *Server) agentByID(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/agents/")
	if strings.HasSuffix(rest, "/compact") {
		id, err := strconv.Atoi(strings.TrimSuffix(rest, "/compact"))
		if err != nil {
			writeText(w, 400, "bad id")
			return
		}
		summary, ok := s.h.compactAgent(id)
		if !ok {
			writeText(w, 500, "compact failed")
			return
		}
		writeJSON(w, 200, models.CompactResp{Summary: summary})
		return
	}
	if strings.HasSuffix(rest, "/clear") {
		id, err := strconv.Atoi(strings.TrimSuffix(rest, "/clear"))
		if err != nil {
			writeText(w, 400, "bad id")
			return
		}
		s.h.SetSession(id, "")
		writeText(w, 200, "ok")
		return
	}
	if r.Method != http.MethodDelete {
		writeText(w, 404, "not found")
		return
	}
	id, err := strconv.Atoi(rest)
	if err != nil {
		writeText(w, 400, "bad id")
		return
	}
	s.h.DeleteAgent(id)
	writeJSON(w, 200, s.h.agentList())
}

func (s *Server) stt(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeText(w, 405, "method not allowed")
		return
	}
	body, _ := io.ReadAll(r.Body)
	writeText(w, 200, s.h.transcribe(body))
}

func (s *Server) sessions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeText(w, 405, "method not allowed")
		return
	}
	dir := strings.TrimSpace(r.URL.Query().Get("dir"))
	if dir == "" {
		dir = home()
	}
	writeJSON(w, 200, s.fs.ListSessions(normalizeDir(dir)))
}

func (s *Server) history(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeText(w, 405, "method not allowed")
		return
	}
	id := r.URL.Query().Get("id")
	if id == "" {
		writeText(w, 400, "missing id")
		return
	}
	writeJSON(w, 200, s.fs.History(normalizeDir(r.URL.Query().Get("dir")), id))
}

func (s *Server) ls(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeText(w, 405, "method not allowed")
		return
	}
	dir := strings.TrimSpace(r.URL.Query().Get("dir"))
	if dir == "" {
		dir = home()
	}
	listing, err := s.fs.ListDir(normalizeDir(dir))
	if err != nil {
		writeText(w, 400, "cannot read dir")
		return
	}
	writeJSON(w, 200, listing)
}

func (s *Server) chat(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeText(w, 405, "method not allowed")
		return
	}
	var p models.ChatReq
	json.NewDecoder(r.Body).Decode(&p)
	w.Header().Set("Content-Type", "application/x-ndjson")
	flusher, _ := w.(http.Flusher)
	s.h.RunChat(p, func(e models.Event) {
		w.Write(mustJSON(e))
		w.Write([]byte("\n"))
		if flusher != nil {
			flusher.Flush()
		}
	})
}
