// claude-voice bridge: Termux-side localhost server for the Android app.
//
// One bridge, one connection from the app, N agents. Each agent is a working
// directory with its own `claude --continue` conversation chain.
//
//	GET    /health                       -> "ok"
//	GET    /agents                       -> [{id,name,dir,branch,dirty}]
//	POST   /agents   {"dir":"~/repo"}    -> create/return agent, then current list
//	DELETE /agents/<id>                  -> remove agent
//	POST   /stt      WAV bytes           -> transcript (whisper.cpp, stateless)
//	POST   /chat     {"text","agent":id} -> agent reply (claude -p, routed to dir)
//
// Single static binary (CGO_ENABLED=0). Shells out to the whisper.cpp cli and
// the claude CLI, both of which run here in Termux where the repos and tools are.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	whisper = env("WHISPER_BIN", expand("~/storage/projects/whisper.cpp/build/bin/whisper-cli"))
	model   = env("WHISPER_MODEL", expand("~/whisper-models/ggml-base.en.bin"))
	perm    = env("VOICE_PERM", "bypassPermissions")
	timeout = envInt("VOICE_TIMEOUT", 180)
	host    = env("VOICE_HOST", "127.0.0.1")
	port    = env("VOICE_PORT", "8765")
)

type agent struct {
	dir     string
	started bool
}

var (
	mu     sync.Mutex
	agents = map[int]*agent{}
	nextID = 1
)

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func envInt(k string, d int) int {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return d
}

func expand(p string) string {
	if strings.HasPrefix(p, "~/") {
		if h, err := os.UserHomeDir(); err == nil {
			return filepath.Join(h, p[2:])
		}
	}
	return p
}

func home() string {
	if h, err := os.UserHomeDir(); err == nil {
		return h
	}
	return "/"
}

func addAgent(dir string) int {
	dir = expand(strings.TrimSpace(dir))
	if abs, err := filepath.Abs(dir); err == nil {
		dir = abs
	}
	mu.Lock()
	defer mu.Unlock()
	for id, a := range agents {
		if a.dir == dir {
			return id
		}
	}
	id := nextID
	nextID++
	agents[id] = &agent{dir: dir}
	return id
}

func gitinfo(dir string) (string, bool) {
	b, err := exec.Command("git", "-C", dir, "rev-parse", "--abbrev-ref", "HEAD").Output()
	if err != nil {
		return "", false
	}
	branch := strings.TrimSpace(string(b))
	s, _ := exec.Command("git", "-C", dir, "status", "--porcelain").Output()
	return branch, len(strings.TrimSpace(string(s))) > 0
}

type agentInfo struct {
	ID     int     `json:"id"`
	Dir    string  `json:"dir"`
	Name   string  `json:"name"`
	Branch *string `json:"branch"`
	Dirty  bool    `json:"dirty"`
}

func agentList() []agentInfo {
	mu.Lock()
	dirs := map[int]string{}
	ids := make([]int, 0, len(agents))
	for id, a := range agents {
		ids = append(ids, id)
		dirs[id] = a.dir
	}
	mu.Unlock()
	sort.Ints(ids)
	out := []agentInfo{}
	for _, id := range ids {
		dir := dirs[id]
		branch, dirty := gitinfo(dir)
		var bp *string
		if branch != "" {
			b := branch
			bp = &b
		}
		name := filepath.Base(dir)
		if name == "" || name == string(filepath.Separator) {
			name = dir
		}
		out = append(out, agentInfo{ID: id, Dir: dir, Name: name, Branch: bp, Dirty: dirty})
	}
	return out
}

func transcribe(wav []byte) string {
	d, err := os.MkdirTemp("", "cv")
	if err != nil {
		return ""
	}
	defer os.RemoveAll(d)
	in := filepath.Join(d, "in.wav")
	outp := filepath.Join(d, "out")
	if err := os.WriteFile(in, wav, 0o600); err != nil {
		return ""
	}
	exec.Command(whisper, "-m", model, "-f", in, "-l", "en", "-nt", "-np", "-otxt", "-of", outp).Run()
	txt, err := os.ReadFile(outp + ".txt")
	if err != nil {
		return ""
	}
	s := string(txt)
	for _, junk := range []string{"[BLANK_AUDIO]", "(silence)"} {
		s = strings.ReplaceAll(s, junk, "")
	}
	return strings.Join(strings.Fields(s), " ")
}

func ask(id int, text string) string {
	if text == "" {
		return ""
	}
	mu.Lock()
	a := agents[id]
	var dir string
	var started bool
	if a != nil {
		dir, started = a.dir, a.started
	}
	mu.Unlock()
	if a == nil {
		return "Unknown agent."
	}
	args := []string{"-p"}
	if started {
		args = append(args, "--continue")
	}
	args = append(args, "--permission-mode", perm, text)
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "claude", args...)
	cmd.Dir = dir
	out, err := cmd.Output()
	if ctx.Err() == context.DeadlineExceeded {
		return fmt.Sprintf("The agent took longer than %d seconds, so I stopped it. Try a smaller step.", timeout)
	}
	mu.Lock()
	if agents[id] != nil {
		agents[id].started = true
	}
	mu.Unlock()
	if s := strings.TrimSpace(string(out)); s != "" {
		return s
	}
	if ee, ok := err.(*exec.ExitError); ok {
		if m := strings.TrimSpace(string(ee.Stderr)); m != "" {
			return m
		}
	}
	return "No response."
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

func agentsHandler(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, 200, agentList())
	case http.MethodPost:
		var p struct {
			Dir string `json:"dir"`
		}
		json.NewDecoder(r.Body).Decode(&p)
		if strings.TrimSpace(p.Dir) == "" {
			writeText(w, 400, "missing dir")
			return
		}
		addAgent(p.Dir)
		writeJSON(w, 200, agentList())
	default:
		writeText(w, 405, "method not allowed")
	}
}

func agentDeleteHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeText(w, 404, "not found")
		return
	}
	id, err := strconv.Atoi(strings.TrimPrefix(r.URL.Path, "/agents/"))
	if err != nil {
		writeText(w, 400, "bad id")
		return
	}
	mu.Lock()
	delete(agents, id)
	mu.Unlock()
	writeJSON(w, 200, agentList())
}

func sttHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeText(w, 405, "method not allowed")
		return
	}
	body, _ := io.ReadAll(r.Body)
	writeText(w, 200, transcribe(body))
}

func lsHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeText(w, 405, "method not allowed")
		return
	}
	dir := strings.TrimSpace(r.URL.Query().Get("dir"))
	if dir == "" {
		dir = home()
	}
	dir = expand(dir)
	if abs, err := filepath.Abs(dir); err == nil {
		dir = abs
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		writeText(w, 400, "cannot read dir")
		return
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
	writeJSON(w, 200, map[string]interface{}{"dir": dir, "parent": parent, "dirs": dirs})
}

func chatHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeText(w, 405, "method not allowed")
		return
	}
	var p struct {
		Text  string `json:"text"`
		Agent *int   `json:"agent"`
	}
	json.NewDecoder(r.Body).Decode(&p)
	id := 0
	if p.Agent != nil {
		id = *p.Agent
	} else if lst := agentList(); len(lst) > 0 {
		id = lst[0].ID
	}
	writeText(w, 200, ask(id, p.Text))
}

func main() {
	start := env("VOICE_WORKDIR", "")
	if start == "" {
		start = home()
	}
	addAgent(start)

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) { writeText(w, 200, "ok") })
	mux.HandleFunc("/agents", agentsHandler)
	mux.HandleFunc("/agents/", agentDeleteHandler)
	mux.HandleFunc("/ls", lsHandler)
	mux.HandleFunc("/stt", sttHandler)
	mux.HandleFunc("/chat", chatHandler)

	addr := host + ":" + port
	fmt.Printf("claude-voice bridge on http://%s  (perm=%s, start_dir=%s)\n", addr, perm, start)
	if err := http.ListenAndServe(addr, mux); err != nil {
		fmt.Fprintln(os.Stderr, "server error:", err)
		os.Exit(1)
	}
}
