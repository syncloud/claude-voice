package bridge

import "net/http"

// Server wires HTTP routes to a Handlers implementation. It holds no business
// logic itself — only the routing table.
type Server struct {
	addr string
	h    *Handlers
}

// NewServer injects the Handlers implementation behind the routes.
func NewServer(addr string, h *Handlers) *Server {
	return &Server{addr: addr, h: h}
}

// Handler builds the route table mapping each path to a Handlers method.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.h.Health)
	mux.HandleFunc("/agents", s.h.Agents)
	mux.HandleFunc("/agents/", s.h.AgentByID)
	mux.HandleFunc("/ls", s.h.Ls)
	mux.HandleFunc("/sessions", s.h.Sessions)
	mux.HandleFunc("/history", s.h.History)
	mux.HandleFunc("/stt", s.h.Stt)
	mux.HandleFunc("/chat", s.h.Chat)
	mux.HandleFunc("/tts", s.h.Tts)
	mux.HandleFunc("/voices", s.h.Voices)
	return mux
}

// ListenAndServe starts the HTTP server on the configured address.
func (s *Server) ListenAndServe() error {
	return http.ListenAndServe(s.addr, s.Handler())
}
