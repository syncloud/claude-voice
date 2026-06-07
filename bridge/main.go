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
	"bufio"
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

	piperBin    = env("PIPER_BIN", expand("~/piper/piper"))
	piperLib    = env("PIPER_LIB", filepath.Dir(piperBin))
	piperEspeak = env("PIPER_ESPEAK", filepath.Join(filepath.Dir(piperBin), "espeak-ng-data"))
	piperVoices = env("PIPER_VOICES", expand("~/piper-voices"))
	piperModel  = env("PIPER_MODEL", "")
)

type agent struct {
	dir     string
	session string
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
	Exists bool    `json:"exists"`
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
		out = append(out, agentInfo{ID: id, Dir: dir, Name: name, Branch: bp, Dirty: dirty, Exists: fileExists(dir)})
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

func trunc(s string, n int) string {
	s = strings.TrimSpace(strings.ReplaceAll(s, "\n", " "))
	if len(s) > n {
		return s[:n] + "…"
	}
	return s
}

func toolLabel(name string, in map[string]interface{}) string {
	s := func(k string) string { v, _ := in[k].(string); return v }
	switch name {
	case "Bash":
		return "Bash: " + trunc(s("command"), 100)
	case "Read":
		return "Read " + filepath.Base(s("file_path"))
	case "Edit", "Write", "MultiEdit", "NotebookEdit":
		return name + " " + filepath.Base(s("file_path"))
	case "Grep":
		return "Grep " + s("pattern")
	case "Glob":
		return "Glob " + s("pattern")
	case "WebFetch", "WebSearch":
		return name + " " + s("url") + s("query")
	case "Task":
		return "Task: " + trunc(s("description"), 70)
	default:
		return name
	}
}

func diffPatch(name string, in map[string]interface{}) (string, string, bool) {
	s := func(k string) string { v, _ := in[k].(string); return v }
	file := filepath.Base(s("file_path"))
	minus := func(t string) string {
		var b strings.Builder
		for _, l := range strings.Split(strings.TrimRight(t, "\n"), "\n") {
			b.WriteString("- " + l + "\n")
		}
		return b.String()
	}
	plus := func(t string) string {
		var b strings.Builder
		for _, l := range strings.Split(strings.TrimRight(t, "\n"), "\n") {
			b.WriteString("+ " + l + "\n")
		}
		return b.String()
	}
	cap := func(p string) string {
		lines := strings.Split(strings.TrimRight(p, "\n"), "\n")
		if len(lines) > 40 {
			lines = append(lines[:40], "… (truncated)")
		}
		return strings.Join(lines, "\n")
	}
	switch name {
	case "Edit":
		return cap(minus(s("old_string")) + plus(s("new_string"))), file, true
	case "Write":
		return cap(plus(s("content"))), file, true
	case "MultiEdit":
		edits, _ := in["edits"].([]interface{})
		var b strings.Builder
		for _, e := range edits {
			m, _ := e.(map[string]interface{})
			o, _ := m["old_string"].(string)
			ns, _ := m["new_string"].(string)
			b.WriteString(minus(o) + plus(ns))
		}
		return cap(b.String()), file, true
	}
	return "", "", false
}

func mustJSON(v interface{}) []byte {
	b, _ := json.Marshal(v)
	return b
}

func usageTokens(u map[string]interface{}) (int, int) {
	n := func(k string) int {
		if v, ok := u[k].(float64); ok {
			return int(v)
		}
		return 0
	}
	in := n("input_tokens") + n("cache_read_input_tokens") + n("cache_creation_input_tokens")
	return in, n("output_tokens")
}

func transform(line []byte) [][]byte {
	var ev map[string]interface{}
	if json.Unmarshal(line, &ev) != nil {
		return nil
	}
	out := [][]byte{}
	switch ev["type"] {
	case "assistant":
		msg, _ := ev["message"].(map[string]interface{})
		content, _ := msg["content"].([]interface{})
		for _, c := range content {
			block, _ := c.(map[string]interface{})
			if block["type"] != "tool_use" {
				continue
			}
			name, _ := block["name"].(string)
			in, _ := block["input"].(map[string]interface{})
			out = append(out, mustJSON(map[string]string{"t": "action", "label": toolLabel(name, in)}))
			if patch, file, ok := diffPatch(name, in); ok {
				out = append(out, mustJSON(map[string]string{"t": "diff", "file": file, "patch": patch}))
			}
		}
		if u, ok := msg["usage"].(map[string]interface{}); ok {
			ti, to := usageTokens(u)
			out = append(out, mustJSON(map[string]interface{}{"t": "usage", "in": ti, "out": to}))
		}
	case "result":
		maxCtx := 0
		if mu, ok := ev["modelUsage"].(map[string]interface{}); ok {
			for _, v := range mu {
				if m, ok := v.(map[string]interface{}); ok {
					if cw, ok := m["contextWindow"].(float64); ok {
						maxCtx = int(cw)
					}
				}
			}
		}
		if u, ok := ev["usage"].(map[string]interface{}); ok {
			ti, to := usageTokens(u)
			out = append(out, mustJSON(map[string]interface{}{"t": "usage", "in": ti, "out": to, "max": maxCtx}))
		}
		res, _ := ev["result"].(string)
		out = append(out, mustJSON(map[string]string{"t": "reply", "text": res}))
	}
	return out
}

func piperEnabled() bool {
	_, err := os.Stat(piperBin)
	return err == nil
}

func listVoices() []string {
	entries, err := os.ReadDir(piperVoices)
	if err != nil {
		return []string{}
	}
	out := []string{}
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".onnx") {
			out = append(out, strings.TrimSuffix(e.Name(), ".onnx"))
		}
	}
	sort.Strings(out)
	return out
}

func resolveVoice(name string) string {
	if name != "" {
		if p := filepath.Join(piperVoices, name+".onnx"); fileExists(p) {
			return p
		}
	}
	if piperModel != "" && fileExists(piperModel) {
		return piperModel
	}
	for _, v := range listVoices() {
		return filepath.Join(piperVoices, v+".onnx")
	}
	return ""
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func synth(text, voice string) ([]byte, error) {
	model := resolveVoice(voice)
	if model == "" {
		return nil, fmt.Errorf("no voice model")
	}
	d, err := os.MkdirTemp("", "tts")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(d)
	wav := filepath.Join(d, "o.wav")
	cmd := exec.Command("grun", piperBin, "-m", model, "--espeak_data", piperEspeak, "-f", wav)
	cmd.Env = append(os.Environ(), "LD_LIBRARY_PATH="+piperLib)
	cmd.Stdin = strings.NewReader(text)
	if err := cmd.Run(); err != nil {
		return nil, err
	}
	return os.ReadFile(wav)
}

func voicesHandler(w http.ResponseWriter, r *http.Request) {
	if !piperEnabled() {
		writeJSON(w, 200, []string{})
		return
	}
	writeJSON(w, 200, listVoices())
}

func ttsHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeText(w, 405, "method not allowed")
		return
	}
	if !piperEnabled() {
		writeText(w, 501, "piper not configured")
		return
	}
	var p struct {
		Text  string `json:"text"`
		Voice string `json:"voice"`
	}
	json.NewDecoder(r.Body).Decode(&p)
	if strings.TrimSpace(p.Text) == "" {
		writeText(w, 400, "empty text")
		return
	}
	wav, err := synth(p.Text, p.Voice)
	if err != nil {
		writeText(w, 500, "tts failed")
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	w.Header().Set("Content-Length", strconv.Itoa(len(wav)))
	w.WriteHeader(200)
	w.Write(wav)
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
			Dir     string `json:"dir"`
			Session string `json:"session"`
		}
		json.NewDecoder(r.Body).Decode(&p)
		if strings.TrimSpace(p.Dir) == "" {
			writeText(w, 400, "missing dir")
			return
		}
		aid := addAgent(p.Dir)
		if p.Session != "" {
			mu.Lock()
			if agents[aid] != nil {
				agents[aid].session = p.Session
			}
			mu.Unlock()
		}
		writeJSON(w, 200, agentList())
	default:
		writeText(w, 405, "method not allowed")
	}
}

func runClaudeSession(dir, resume, text string) (string, string, error) {
	args := []string{"-p", "--output-format", "json"}
	if resume != "" {
		args = append(args, "--resume", resume)
	}
	args = append(args, "--permission-mode", perm, text)
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "claude", args...)
	cmd.Dir = dir
	out, err := cmd.Output()
	if err != nil {
		return "", "", err
	}
	var res map[string]interface{}
	if json.Unmarshal(out, &res) != nil {
		return strings.TrimSpace(string(out)), "", nil
	}
	t, _ := res["result"].(string)
	s, _ := res["session_id"].(string)
	return strings.TrimSpace(t), s, nil
}

func compactAgent(id int) (string, bool) {
	mu.Lock()
	a := agents[id]
	var dir, sess string
	if a != nil {
		dir, sess = a.dir, a.session
	}
	mu.Unlock()
	if a == nil {
		return "", false
	}
	summary, _, err := runClaudeSession(dir, sess,
		"Summarize our conversation so far as concisely as possible — key decisions, "+
			"important context, and the current state — so a fresh session can continue "+
			"seamlessly. Output only the summary, no preamble.")
	if err != nil || summary == "" {
		return "", false
	}
	_, newSess, _ := runClaudeSession(dir, "",
		"Context from our earlier conversation (summarized):\n\n"+summary+
			"\n\nAcknowledge with just: ready.")
	mu.Lock()
	if agents[id] != nil {
		agents[id].session = newSess
	}
	mu.Unlock()
	return summary, true
}

func agentDeleteHandler(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/agents/")
	if strings.HasSuffix(rest, "/compact") {
		id, err := strconv.Atoi(strings.TrimSuffix(rest, "/compact"))
		if err != nil {
			writeText(w, 400, "bad id")
			return
		}
		summary, ok := compactAgent(id)
		if !ok {
			writeText(w, 500, "compact failed")
			return
		}
		writeJSON(w, 200, map[string]string{"summary": summary})
		return
	}
	if strings.HasSuffix(rest, "/clear") {
		id, err := strconv.Atoi(strings.TrimSuffix(rest, "/clear"))
		if err != nil {
			writeText(w, 400, "bad id")
			return
		}
		mu.Lock()
		if a := agents[id]; a != nil {
			a.session = ""
		}
		mu.Unlock()
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

func encodeDir(d string) string {
	return strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			return r
		}
		return '-'
	}, d)
}

type sessionInfo struct {
	ID      string `json:"id"`
	Preview string `json:"preview"`
	Mtime   int64  `json:"mtime"`
}

func sessionPreview(path string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
	for sc.Scan() {
		var m map[string]interface{}
		if json.Unmarshal(sc.Bytes(), &m) != nil || m["type"] != "user" {
			continue
		}
		msg, _ := m["message"].(map[string]interface{})
		switch c := msg["content"].(type) {
		case string:
			if strings.TrimSpace(c) != "" {
				return trunc(c, 70)
			}
		case []interface{}:
			for _, b := range c {
				bm, _ := b.(map[string]interface{})
				if bm["type"] == "text" {
					if t, ok := bm["text"].(string); ok && strings.TrimSpace(t) != "" {
						return trunc(t, 70)
					}
				}
			}
		}
	}
	return ""
}

func listSessions(dir string) []sessionInfo {
	proj := filepath.Join(home(), ".claude", "projects", encodeDir(dir))
	entries, err := os.ReadDir(proj)
	if err != nil {
		return []sessionInfo{}
	}
	out := []sessionInfo{}
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".jsonl") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, sessionInfo{
			ID:      strings.TrimSuffix(e.Name(), ".jsonl"),
			Preview: sessionPreview(filepath.Join(proj, e.Name())),
			Mtime:   info.ModTime().Unix(),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Mtime > out[j].Mtime })
	if len(out) > 20 {
		out = out[:20]
	}
	return out
}

func sessionsHandler(w http.ResponseWriter, r *http.Request) {
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
	writeJSON(w, 200, listSessions(dir))
}

func historyEvents(dir, id string) [][]byte {
	path := filepath.Join(home(), ".claude", "projects", encodeDir(dir), id+".jsonl")
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	out := [][]byte{}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	for sc.Scan() {
		var ev map[string]interface{}
		if json.Unmarshal(sc.Bytes(), &ev) != nil {
			continue
		}
		switch ev["type"] {
		case "user":
			msg, _ := ev["message"].(map[string]interface{})
			switch c := msg["content"].(type) {
			case string:
				if strings.TrimSpace(c) != "" {
					out = append(out, mustJSON(map[string]string{"t": "you", "text": c}))
				}
			case []interface{}:
				for _, b := range c {
					bm, _ := b.(map[string]interface{})
					if bm["type"] == "text" {
						if t, ok := bm["text"].(string); ok && strings.TrimSpace(t) != "" {
							out = append(out, mustJSON(map[string]string{"t": "you", "text": t}))
						}
					}
				}
			}
		case "assistant":
			msg, _ := ev["message"].(map[string]interface{})
			content, _ := msg["content"].([]interface{})
			for _, b := range content {
				bm, _ := b.(map[string]interface{})
				switch bm["type"] {
				case "text":
					if t, ok := bm["text"].(string); ok && strings.TrimSpace(t) != "" {
						out = append(out, mustJSON(map[string]string{"t": "reply", "text": t}))
					}
				case "tool_use":
					name, _ := bm["name"].(string)
					in, _ := bm["input"].(map[string]interface{})
					out = append(out, mustJSON(map[string]string{"t": "action", "label": toolLabel(name, in)}))
					if patch, file, ok := diffPatch(name, in); ok {
						out = append(out, mustJSON(map[string]string{"t": "diff", "file": file, "patch": patch}))
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

func historyHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeText(w, 405, "method not allowed")
		return
	}
	dir := expand(strings.TrimSpace(r.URL.Query().Get("dir")))
	if abs, err := filepath.Abs(dir); err == nil {
		dir = abs
	}
	id := r.URL.Query().Get("id")
	if id == "" {
		writeText(w, 400, "missing id")
		return
	}
	evs := historyEvents(dir, id)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Write([]byte("["))
	for i, e := range evs {
		if i > 0 {
			w.Write([]byte(","))
		}
		w.Write(e)
	}
	w.Write([]byte("]"))
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
	mu.Lock()
	a := agents[id]
	var dir, resume string
	if a != nil {
		dir, resume = a.dir, a.session
	}
	mu.Unlock()

	w.Header().Set("Content-Type", "application/x-ndjson")
	flusher, _ := w.(http.Flusher)
	emit := func(b []byte) {
		w.Write(b)
		w.Write([]byte("\n"))
		if flusher != nil {
			flusher.Flush()
		}
	}
	if a == nil || strings.TrimSpace(p.Text) == "" {
		emit(mustJSON(map[string]string{"t": "reply", "text": "Unknown agent."}))
		return
	}

	args := []string{"-p", "--output-format", "stream-json", "--verbose"}
	if resume != "" {
		args = append(args, "--resume", resume)
	}
	args = append(args, "--permission-mode", perm, p.Text)
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "claude", args...)
	cmd.Dir = dir
	stdout, err := cmd.StdoutPipe()
	if err != nil || cmd.Start() != nil {
		emit(mustJSON(map[string]string{"t": "reply", "text": "Failed to start agent."}))
		return
	}
	sc := bufio.NewScanner(stdout)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	sawReply := false
	newSession := ""
	for sc.Scan() {
		line := sc.Bytes()
		if strings.Contains(string(line), "session_id") {
			var m map[string]interface{}
			if json.Unmarshal(line, &m) == nil {
				if s, ok := m["session_id"].(string); ok && s != "" {
					newSession = s
				}
			}
		}
		for _, e := range transform(line) {
			if strings.Contains(string(e), `"t":"reply"`) {
				sawReply = true
			}
			emit(e)
		}
	}
	cmd.Wait()
	if ctx.Err() == context.DeadlineExceeded {
		emit(mustJSON(map[string]string{"t": "reply", "text": fmt.Sprintf("The agent took longer than %d seconds, so I stopped it. Try a smaller step.", timeout)}))
	} else if !sawReply {
		emit(mustJSON(map[string]string{"t": "reply", "text": "No response."}))
	}
	mu.Lock()
	if agents[id] != nil && newSession != "" {
		agents[id].session = newSession
	}
	mu.Unlock()
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
	mux.HandleFunc("/sessions", sessionsHandler)
	mux.HandleFunc("/history", historyHandler)
	mux.HandleFunc("/stt", sttHandler)
	mux.HandleFunc("/chat", chatHandler)
	mux.HandleFunc("/tts", ttsHandler)
	mux.HandleFunc("/voices", voicesHandler)

	addr := host + ":" + port
	fmt.Printf("claude-voice bridge on http://%s  (perm=%s, start_dir=%s)\n", addr, perm, start)
	if err := http.ListenAndServe(addr, mux); err != nil {
		fmt.Fprintln(os.Stderr, "server error:", err)
		os.Exit(1)
	}
}
