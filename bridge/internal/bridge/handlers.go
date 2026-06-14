package bridge

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

const narrateMarker = "===SPOKEN==="

var working = []string{
	"I'm still working on it.",
	"Still going, hang tight.",
	"Working on it, almost there.",
	"Still busy, give me a moment.",
}

const narrateSystem = `IMPORTANT OUTPUT RULE: Whenever your reply contains any code, you MUST end your message with a line containing exactly ===SPOKEN=== followed by a spoken narration of your entire reply for a developer who cannot see the screen. In the narration, read all code in full natural-language detail: name every declaration, identifier, type, parameter, and return, and describe the control flow and logic; phrase symbols naturally and never read punctuation literally. If your reply contains no code, do NOT add the marker or narration.`

const narratePrompt = `You turn an AI coding assistant's reply into spoken narration for an experienced developer who knows this codebase but cannot see the screen (for example a blind developer pair-programming with AI). Speak the prose naturally. For any code, narrate it completely and precisely — every declaration, identifier, type, parameter, return value, and the control flow and key logic — the way a developer dictates code to a peer, so no detail is lost. Do not spell out punctuation or read symbols literally; phrase them naturally (for example: "assigns", "returns a list of strings", "for each item", "if x is greater than zero", "an arrow function taking req and res"). Use the real identifiers. Announce structure briefly ("function fetchUser, taking an id of type string, returns a User"). Keep it flowing and listenable, preserve order, and output only the narration with no preamble.

Reply to narrate:
`

type agent struct {
	dir     string
	session string
}

// Handlers is the implementation behind the HTTP routes: it owns the bridge
// config and the in-memory agent registry, and shells out to claude/whisper/piper.
type Handlers struct {
	cfg    Config
	mu     sync.Mutex
	agents map[int]*agent
	nextID int
}

// NewHandlers creates a Handlers with the given config and an empty registry.
func NewHandlers(cfg Config) *Handlers {
	return &Handlers{cfg: cfg, agents: map[int]*agent{}, nextID: 1}
}

// AddAgent registers (or returns the existing id for) a working directory.
func (h *Handlers) AddAgent(dir string) int {
	dir = expand(strings.TrimSpace(dir))
	if abs, err := filepath.Abs(dir); err == nil {
		dir = abs
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for id, a := range h.agents {
		if a.dir == dir {
			return id
		}
	}
	id := h.nextID
	h.nextID++
	h.agents[id] = &agent{dir: dir}
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

func (h *Handlers) agentList() []agentInfo {
	h.mu.Lock()
	dirs := map[int]string{}
	ids := make([]int, 0, len(h.agents))
	for id, a := range h.agents {
		ids = append(ids, id)
		dirs[id] = a.dir
	}
	h.mu.Unlock()
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

func (h *Handlers) transcribe(wav []byte) string {
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
	exec.Command(h.cfg.Whisper, "-m", h.cfg.Model, "-f", in, "-l", "en", "-nt", "-np", "-otxt", "-of", outp).Run()
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

func toolLabel(name string, in toolInput) string {
	switch name {
	case "Bash":
		return "Bash: " + trunc(in.Command, 100)
	case "Read":
		return "Read " + filepath.Base(in.FilePath)
	case "Edit", "Write", "MultiEdit", "NotebookEdit":
		return name + " " + filepath.Base(in.FilePath)
	case "Grep":
		return "Grep " + in.Pattern
	case "Glob":
		return "Glob " + in.Pattern
	case "WebFetch", "WebSearch":
		return name + " " + in.URL + in.Query
	case "Task":
		return "Task: " + trunc(in.Description, 70)
	default:
		return name
	}
}

func diffPatch(name string, in toolInput) (string, string, bool) {
	file := filepath.Base(in.FilePath)
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
		return cap(minus(in.OldString) + plus(in.NewString)), file, true
	case "Write":
		return cap(plus(in.Content)), file, true
	case "MultiEdit":
		var b strings.Builder
		for _, e := range in.Edits {
			b.WriteString(minus(e.OldString) + plus(e.NewString))
		}
		return cap(b.String()), file, true
	}
	return "", "", false
}

func mustJSON(v interface{}) []byte {
	b, _ := json.Marshal(v)
	return b
}

// transformEvent turns one parsed claude stream-json event into the typed
// events the app consumes.
func transformEvent(ev streamEvent) []Event {
	out := []Event{}
	switch ev.Type {
	case "assistant":
		for _, b := range ev.Message.blocks() {
			if b.Type != "tool_use" {
				continue
			}
			out = append(out, Event{T: "action", Label: toolLabel(b.Name, b.Input)})
			if patch, file, ok := diffPatch(b.Name, b.Input); ok {
				out = append(out, Event{T: "diff", File: file, Patch: patch})
			}
		}
		if ev.Message != nil && ev.Message.Usage != nil {
			ti, to := ev.Message.Usage.inOut()
			out = append(out, Event{T: "usage", In: intp(ti), Out: intp(to)})
		}
	case "result":
		maxCtx := 0
		for _, m := range ev.ModelUsage {
			if m.ContextWindow > maxCtx {
				maxCtx = m.ContextWindow
			}
		}
		if ev.Usage != nil {
			ti, to := ev.Usage.inOut()
			out = append(out, Event{T: "usage", In: intp(ti), Out: intp(to), Max: intp(maxCtx)})
		}
		display, speech := ev.Result, ""
		if idx := strings.Index(ev.Result, narrateMarker); idx >= 0 {
			display = strings.TrimSpace(ev.Result[:idx])
			speech = strings.TrimSpace(ev.Result[idx+len(narrateMarker):])
		}
		out = append(out, Event{T: "reply", Text: display, Speech: speech})
	}
	return out
}

func (h *Handlers) piperEnabled() bool {
	_, err := os.Stat(h.cfg.PiperBin)
	return err == nil
}

func (h *Handlers) listVoices() []string {
	entries, err := os.ReadDir(h.cfg.PiperVoices)
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

func (h *Handlers) resolveVoice(name string) string {
	if name != "" {
		if p := filepath.Join(h.cfg.PiperVoices, name+".onnx"); fileExists(p) {
			return p
		}
	}
	if h.cfg.PiperModel != "" && fileExists(h.cfg.PiperModel) {
		return h.cfg.PiperModel
	}
	for _, v := range h.listVoices() {
		return filepath.Join(h.cfg.PiperVoices, v+".onnx")
	}
	return ""
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func (h *Handlers) synth(text, voice string) ([]byte, error) {
	model := h.resolveVoice(voice)
	if model == "" {
		return nil, fmt.Errorf("no voice model")
	}
	d, err := os.MkdirTemp("", "tts")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(d)
	wav := filepath.Join(d, "o.wav")
	cmd := exec.Command("grun", h.cfg.PiperBin, "-m", model, "--espeak_data", h.cfg.PiperEspeak, "-f", wav)
	cmd.Env = append(os.Environ(), "LD_LIBRARY_PATH="+h.cfg.PiperLib)
	cmd.Stdin = strings.NewReader(text)
	if err := cmd.Run(); err != nil {
		return nil, err
	}
	return os.ReadFile(wav)
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

func (h *Handlers) Health(w http.ResponseWriter, r *http.Request) {
	writeText(w, 200, "ok")
}

func (h *Handlers) Voices(w http.ResponseWriter, r *http.Request) {
	if !h.piperEnabled() {
		writeJSON(w, 200, []string{})
		return
	}
	writeJSON(w, 200, h.listVoices())
}

func (h *Handlers) Tts(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeText(w, 405, "method not allowed")
		return
	}
	if !h.piperEnabled() {
		writeText(w, 501, "piper not configured")
		return
	}
	var p ttsReq
	json.NewDecoder(r.Body).Decode(&p)
	if strings.TrimSpace(p.Text) == "" {
		writeText(w, 400, "empty text")
		return
	}
	wav, err := h.synth(p.Text, p.Voice)
	if err != nil {
		writeText(w, 500, "tts failed")
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	w.Header().Set("Content-Length", strconv.Itoa(len(wav)))
	w.WriteHeader(200)
	w.Write(wav)
}

func (h *Handlers) Agents(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, 200, h.agentList())
	case http.MethodPost:
		var p agentReq
		json.NewDecoder(r.Body).Decode(&p)
		if strings.TrimSpace(p.Dir) == "" {
			writeText(w, 400, "missing dir")
			return
		}
		aid := h.AddAgent(p.Dir)
		if p.Session != "" {
			h.mu.Lock()
			if h.agents[aid] != nil {
				h.agents[aid].session = p.Session
			}
			h.mu.Unlock()
		}
		writeJSON(w, 200, h.agentList())
	default:
		writeText(w, 405, "method not allowed")
	}
}

func (h *Handlers) runClaudeSession(dir, resume, text string) (string, string, error) {
	args := []string{"-p", "--output-format", "json"}
	if resume != "" {
		args = append(args, "--resume", resume)
	}
	args = append(args, "--permission-mode", h.cfg.Perm, text)
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(h.cfg.Timeout)*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "claude", args...)
	cmd.Dir = dir
	out, err := cmd.Output()
	if err != nil {
		return "", "", err
	}
	var res claudeResult
	if json.Unmarshal(out, &res) != nil {
		return strings.TrimSpace(string(out)), "", nil
	}
	return strings.TrimSpace(res.Result), res.SessionID, nil
}

func (h *Handlers) compactAgent(id int) (string, bool) {
	h.mu.Lock()
	a := h.agents[id]
	var dir, sess string
	if a != nil {
		dir, sess = a.dir, a.session
	}
	h.mu.Unlock()
	if a == nil {
		return "", false
	}
	summary, _, err := h.runClaudeSession(dir, sess,
		"Summarize our conversation so far as concisely as possible — key decisions, "+
			"important context, and the current state — so a fresh session can continue "+
			"seamlessly. Output only the summary, no preamble.")
	if err != nil || summary == "" {
		return "", false
	}
	_, newSess, _ := h.runClaudeSession(dir, "",
		"Context from our earlier conversation (summarized):\n\n"+summary+
			"\n\nAcknowledge with just: ready.")
	h.mu.Lock()
	if h.agents[id] != nil {
		h.agents[id].session = newSess
	}
	h.mu.Unlock()
	return summary, true
}

// AgentByID handles /agents/<id>, /agents/<id>/compact and /agents/<id>/clear.
func (h *Handlers) AgentByID(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/agents/")
	if strings.HasSuffix(rest, "/compact") {
		id, err := strconv.Atoi(strings.TrimSuffix(rest, "/compact"))
		if err != nil {
			writeText(w, 400, "bad id")
			return
		}
		summary, ok := h.compactAgent(id)
		if !ok {
			writeText(w, 500, "compact failed")
			return
		}
		writeJSON(w, 200, compactResp{Summary: summary})
		return
	}
	if strings.HasSuffix(rest, "/clear") {
		id, err := strconv.Atoi(strings.TrimSuffix(rest, "/clear"))
		if err != nil {
			writeText(w, 400, "bad id")
			return
		}
		h.mu.Lock()
		if a := h.agents[id]; a != nil {
			a.session = ""
		}
		h.mu.Unlock()
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
	h.mu.Lock()
	delete(h.agents, id)
	h.mu.Unlock()
	writeJSON(w, 200, h.agentList())
}

func (h *Handlers) narrate(text string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(h.cfg.Timeout)*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "claude", "-p", "--model", h.cfg.NarrateModel,
		"--permission-mode", h.cfg.Perm, narratePrompt+text)
	out, err := cmd.Output()
	return strings.TrimSpace(string(out)), err
}

func (h *Handlers) Narrate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeText(w, 405, "method not allowed")
		return
	}
	var p narrateReq
	json.NewDecoder(r.Body).Decode(&p)
	if strings.TrimSpace(p.Text) == "" {
		writeText(w, 400, "empty text")
		return
	}
	n, err := h.narrate(p.Text)
	if err != nil || n == "" {
		writeText(w, 500, "narrate failed")
		return
	}
	writeText(w, 200, n)
}

func (h *Handlers) Stt(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeText(w, 405, "method not allowed")
		return
	}
	body, _ := io.ReadAll(r.Body)
	writeText(w, 200, h.transcribe(body))
}

func encodeDir(d string) string {
	return strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			return r
		}
		return '-'
	}, d)
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
		var ev streamEvent
		if json.Unmarshal(sc.Bytes(), &ev) != nil || ev.Type != "user" {
			continue
		}
		if s := ev.Message.textContent(); strings.TrimSpace(s) != "" {
			return trunc(s, 70)
		}
		for _, b := range ev.Message.blocks() {
			if b.Type == "text" && strings.TrimSpace(b.Text) != "" {
				return trunc(b.Text, 70)
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

func (h *Handlers) Sessions(w http.ResponseWriter, r *http.Request) {
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

func historyEvents(dir, id string) []Event {
	path := filepath.Join(home(), ".claude", "projects", encodeDir(dir), id+".jsonl")
	f, err := os.Open(path)
	if err != nil {
		return []Event{}
	}
	defer f.Close()
	out := []Event{}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	for sc.Scan() {
		var ev streamEvent
		if json.Unmarshal(sc.Bytes(), &ev) != nil {
			continue
		}
		switch ev.Type {
		case "user":
			if s := ev.Message.textContent(); strings.TrimSpace(s) != "" {
				out = append(out, Event{T: "you", Text: s})
				continue
			}
			for _, b := range ev.Message.blocks() {
				if b.Type == "text" && strings.TrimSpace(b.Text) != "" {
					out = append(out, Event{T: "you", Text: b.Text})
				}
			}
		case "assistant":
			for _, b := range ev.Message.blocks() {
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
						out = append(out, Event{T: "reply", Text: t})
					}
				case "tool_use":
					out = append(out, Event{T: "action", Label: toolLabel(b.Name, b.Input)})
					if patch, file, ok := diffPatch(b.Name, b.Input); ok {
						out = append(out, Event{T: "diff", File: file, Patch: patch})
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

func (h *Handlers) History(w http.ResponseWriter, r *http.Request) {
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
	writeJSON(w, 200, historyEvents(dir, id))
}

func (h *Handlers) Ls(w http.ResponseWriter, r *http.Request) {
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
	writeJSON(w, 200, dirListing{Dir: dir, Parent: parent, Dirs: dirs})
}

func (h *Handlers) Chat(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeText(w, 405, "method not allowed")
		return
	}
	var p chatReq
	json.NewDecoder(r.Body).Decode(&p)
	id := 0
	if p.Agent != nil {
		id = *p.Agent
	} else if lst := h.agentList(); len(lst) > 0 {
		id = lst[0].ID
	}
	h.mu.Lock()
	a := h.agents[id]
	var dir, resume string
	if a != nil {
		dir, resume = a.dir, a.session
	}
	h.mu.Unlock()

	w.Header().Set("Content-Type", "application/x-ndjson")
	flusher, _ := w.(http.Flusher)
	emit := func(e Event) {
		w.Write(mustJSON(e))
		w.Write([]byte("\n"))
		if flusher != nil {
			flusher.Flush()
		}
	}
	if a == nil || strings.TrimSpace(p.Text) == "" {
		emit(Event{T: "reply", Text: "Unknown agent."})
		return
	}

	args := []string{"-p", "--output-format", "stream-json", "--verbose"}
	if resume != "" {
		args = append(args, "--resume", resume)
	}
	if strings.TrimSpace(p.Model) != "" {
		args = append(args, "--model", p.Model)
	}
	doNarrate := h.cfg.NarrateOn
	if p.Narrate != nil {
		doNarrate = *p.Narrate
	}
	if doNarrate {
		args = append(args, "--append-system-prompt", narrateSystem)
	}
	args = append(args, "--permission-mode", h.cfg.Perm, p.Text)
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(h.cfg.Timeout)*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "claude", args...)
	cmd.Dir = dir
	stdout, err := cmd.StdoutPipe()
	if err != nil || cmd.Start() != nil {
		emit(Event{T: "reply", Text: "Failed to start agent."})
		return
	}
	sc := bufio.NewScanner(stdout)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	sawReply := false
	sentModel := false
	newSession := ""
	lines := make(chan []byte, 64)
	go func() {
		defer close(lines)
		for sc.Scan() {
			b := make([]byte, len(sc.Bytes()))
			copy(b, sc.Bytes())
			lines <- b
		}
	}()
	heartbeat := time.NewTicker(60 * time.Second)
	defer heartbeat.Stop()
	hbn := 0
	scanning := true
	for scanning {
		select {
		case line, ok := <-lines:
			if !ok {
				scanning = false
				break
			}
			var ev streamEvent
			if json.Unmarshal(line, &ev) != nil {
				continue
			}
			if ev.SessionID != "" {
				newSession = ev.SessionID
			}
			if !sentModel {
				name := ev.Model
				if name == "" && ev.Message != nil {
					name = ev.Message.Model
				}
				if name != "" {
					sentModel = true
					emit(Event{T: "model", Name: name})
				}
			}
			for _, e := range transformEvent(ev) {
				if e.T == "reply" {
					sawReply = true
				}
				emit(e)
			}
		case <-heartbeat.C:
			emit(Event{T: "working", Text: working[hbn%len(working)]})
			hbn++
		}
	}
	cmd.Wait()
	if ctx.Err() == context.DeadlineExceeded {
		emit(Event{T: "reply", Text: fmt.Sprintf("The agent took longer than %d seconds, so I stopped it. Try a smaller step.", h.cfg.Timeout)})
	} else if !sawReply {
		emit(Event{T: "reply", Text: "No response."})
	}
	h.mu.Lock()
	if h.agents[id] != nil && newSession != "" {
		h.agents[id].session = newSession
	}
	h.mu.Unlock()
}
