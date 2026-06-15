package bridge

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/cyberb/claude-voice/bridge/internal/bridge/models"
)

func intp(v int) *int { return &v }

const narrateMarker = "===SPOKEN==="

var working = []string{
	"I'm still working on it.",
	"Still going, hang tight.",
	"Working on it, almost there.",
	"Still busy, give me a moment.",
}

const narrateSystem = `IMPORTANT OUTPUT RULE: Whenever your reply contains any code, you MUST end your message with a line containing exactly ===SPOKEN=== followed by a spoken narration of your entire reply for a developer who cannot see the screen. In the narration, read all code in full natural-language detail: name every declaration, identifier, type, parameter, and return, and describe the control flow and logic; phrase symbols naturally and never read punctuation literally. If your reply contains no code, do NOT add the marker or narration.`

type agent struct {
	dir     string
	session string
}

// Handlers owns the bridge config and the in-memory agent registry, and shells
// out to claude/whisper/piper. It deals only in normal Go types and has no
// knowledge of HTTP — the Server adapts these methods to routes. All disk
// access goes through the injected FS.
type Handlers struct {
	cfg    Config
	fs     *FS
	mu     sync.Mutex
	agents map[int]*agent
	nextID int
}

// NewHandlers creates a Handlers with the given config, filesystem and an empty
// registry.
func NewHandlers(cfg Config, fs *FS) *Handlers {
	return &Handlers{cfg: cfg, fs: fs, agents: map[int]*agent{}, nextID: 1}
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

// SetSession records the claude session id for an agent (no-op if unknown).
func (h *Handlers) SetSession(id int, session string) {
	h.mu.Lock()
	if a := h.agents[id]; a != nil {
		a.session = session
	}
	h.mu.Unlock()
}

// DeleteAgent removes an agent from the registry.
func (h *Handlers) DeleteAgent(id int) {
	h.mu.Lock()
	delete(h.agents, id)
	h.mu.Unlock()
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

func (h *Handlers) agentList() []models.AgentInfo {
	h.mu.Lock()
	dirs := map[int]string{}
	ids := make([]int, 0, len(h.agents))
	for id, a := range h.agents {
		ids = append(ids, id)
		dirs[id] = a.dir
	}
	h.mu.Unlock()
	sort.Ints(ids)
	out := []models.AgentInfo{}
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
		out = append(out, models.AgentInfo{ID: id, Dir: dir, Name: name, Branch: bp, Dirty: dirty, Exists: h.fs.Exists(dir)})
	}
	return out
}

func (h *Handlers) transcribe(wav []byte) string {
	d, err := h.fs.TempDir("cv")
	if err != nil {
		return ""
	}
	defer h.fs.RemoveAll(d)
	in := filepath.Join(d, "in.wav")
	outp := filepath.Join(d, "out")
	if err := h.fs.WriteFile(in, wav); err != nil {
		return ""
	}
	exec.Command(h.cfg.Whisper, "-m", h.cfg.Model, "-f", in, "-l", "en", "-nt", "-np", "-otxt", "-of", outp).Run()
	txt, err := h.fs.ReadFile(outp + ".txt")
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

func toolLabel(name string, in models.ToolInput) string {
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

func diffPatch(name string, in models.ToolInput) (string, string, bool) {
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

// transformEvent turns one parsed claude stream-json event into the typed
// events the app consumes.
func transformEvent(ev models.StreamEvent) []models.Event {
	out := []models.Event{}
	switch ev.Type {
	case "assistant":
		for _, b := range ev.Message.Blocks() {
			if b.Type != "tool_use" {
				continue
			}
			out = append(out, models.Event{T: "action", Label: toolLabel(b.Name, b.Input)})
			if patch, file, ok := diffPatch(b.Name, b.Input); ok {
				out = append(out, models.Event{T: "diff", File: file, Patch: patch})
			}
		}
		if ev.Message != nil && ev.Message.Usage != nil {
			ti, to := ev.Message.Usage.InOut()
			out = append(out, models.Event{T: "usage", In: intp(ti), Out: intp(to)})
		}
	case "result":
		maxCtx := 0
		for _, m := range ev.ModelUsage {
			if m.ContextWindow > maxCtx {
				maxCtx = m.ContextWindow
			}
		}
		if ev.Usage != nil {
			ti, to := ev.Usage.InOut()
			out = append(out, models.Event{T: "usage", In: intp(ti), Out: intp(to), Max: intp(maxCtx)})
		}
		display, speech := ev.Result, ""
		if idx := strings.Index(ev.Result, narrateMarker); idx >= 0 {
			display = strings.TrimSpace(ev.Result[:idx])
			speech = strings.TrimSpace(ev.Result[idx+len(narrateMarker):])
		}
		out = append(out, models.Event{T: "reply", Text: display, Speech: speech})
	}
	return out
}

func (h *Handlers) synth(text, voice string) ([]byte, error) {
	model := h.fs.ResolveVoice(voice)
	if model == "" {
		return nil, fmt.Errorf("no voice model")
	}
	d, err := h.fs.TempDir("tts")
	if err != nil {
		return nil, err
	}
	defer h.fs.RemoveAll(d)
	wav := filepath.Join(d, "o.wav")
	cmd := exec.Command("grun", h.cfg.PiperBin, "-m", model, "--espeak_data", h.cfg.PiperEspeak, "-f", wav)
	cmd.Env = append(os.Environ(), "LD_LIBRARY_PATH="+h.cfg.PiperLib)
	cmd.Stdin = strings.NewReader(text)
	if err := cmd.Run(); err != nil {
		return nil, err
	}
	return h.fs.ReadFile(wav)
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
	var res models.ClaudeResult
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

// RunChat runs a claude streaming turn for the request and delivers each typed
// event to emit. It blocks until the turn completes and persists the resulting
// session id. emit must be safe to call from this goroutine only.
func (h *Handlers) RunChat(p models.ChatReq, emit func(models.Event)) {
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

	if a == nil || strings.TrimSpace(p.Text) == "" {
		emit(models.Event{T: "reply", Text: "Unknown agent."})
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
		emit(models.Event{T: "reply", Text: "Failed to start agent."})
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
			var ev models.StreamEvent
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
					emit(models.Event{T: "model", Name: name})
				}
			}
			for _, e := range transformEvent(ev) {
				if e.T == "reply" {
					sawReply = true
				}
				emit(e)
			}
		case <-heartbeat.C:
			emit(models.Event{T: "working", Text: working[hbn%len(working)]})
			hbn++
		}
	}
	cmd.Wait()
	if ctx.Err() == context.DeadlineExceeded {
		emit(models.Event{T: "reply", Text: fmt.Sprintf("The agent took longer than %d seconds, so I stopped it. Try a smaller step.", h.cfg.Timeout)})
	} else if !sawReply {
		emit(models.Event{T: "reply", Text: "No response."})
	}
	h.mu.Lock()
	if h.agents[id] != nil && newSession != "" {
		h.agents[id].session = newSession
	}
	h.mu.Unlock()
}
