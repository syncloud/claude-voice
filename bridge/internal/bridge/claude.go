package bridge

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"strings"
	"time"

	"github.com/syncloud/claude-voice/bridge/internal/bridge/model"
)

var working = []string{
	"I'm still working on it.",
	"Still going, hang tight.",
	"Working on it, almost there.",
	"Still busy, give me a moment.",
}

const narrateSystem = `IMPORTANT OUTPUT RULE: Whenever your reply contains any code, you MUST end your message with a line containing exactly ===SPOKEN=== followed by a spoken narration of your entire reply for a developer who cannot see the screen. In the narration, read all code in full natural-language detail: name every declaration, identifier, type, parameter, and return, and describe the control flow and logic; phrase symbols naturally and never read punctuation literally. If your reply contains no code, do NOT add the marker or narration.`

// Claude drives the claude CLI: streaming chat turns, one-shot sessions and
// conversation compaction. Resulting session ids are persisted on the registry.
type Claude struct {
	cfg    Config
	agents *Agents
}

// NewClaude wires claude runtime settings from cfg and the agent registry.
func NewClaude(cfg Config, agents *Agents) *Claude {
	return &Claude{cfg: cfg, agents: agents}
}

// Session runs a one-shot `claude -p` turn and returns (result, sessionID).
func (c *Claude) Session(dir, resume, text string) (string, string, error) {
	args := []string{"-p", "--output-format", "json"}
	if resume != "" {
		args = append(args, "--resume", resume)
	}
	args = append(args, "--permission-mode", c.cfg.Perm, text)
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(c.cfg.Timeout)*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "claude", args...)
	cmd.Dir = dir
	out, err := cmd.Output()
	if err != nil {
		return "", "", err
	}
	var res model.ClaudeResult
	if json.Unmarshal(out, &res) != nil {
		return strings.TrimSpace(string(out)), "", nil
	}
	return strings.TrimSpace(res.Result), res.SessionID, nil
}

// Compact summarizes an agent's conversation and starts a fresh session seeded
// with that summary, returning the summary.
func (c *Claude) Compact(id int) (string, bool) {
	dir, sess, ok := c.agents.lookup(id)
	if !ok {
		return "", false
	}
	summary, _, err := c.Session(dir, sess,
		"Summarize our conversation so far as concisely as possible — key decisions, "+
			"important context, and the current state — so a fresh session can continue "+
			"seamlessly. Output only the summary, no preamble.")
	if err != nil || summary == "" {
		return "", false
	}
	_, newSess, _ := c.Session(dir, "",
		"Context from our earlier conversation (summarized):\n\n"+summary+
			"\n\nAcknowledge with just: ready.")
	c.agents.SetSession(id, newSess)
	return summary, true
}

// Chat runs a claude streaming turn and delivers each typed event to emit. It
// blocks until the turn completes and persists the resulting session id. emit
// must be safe to call from this goroutine only.
func (c *Claude) Chat(p model.ChatReq, emit func(model.Event)) {
	id := 0
	if p.Agent != nil {
		id = *p.Agent
	} else if lst := c.agents.List(); len(lst) > 0 {
		id = lst[0].ID
	}
	dir, resume, ok := c.agents.lookup(id)
	if !ok || strings.TrimSpace(p.Text) == "" {
		emit(model.Event{T: "reply", Text: "Unknown agent."})
		return
	}

	args := []string{"-p", "--output-format", "stream-json", "--verbose"}
	if resume != "" {
		args = append(args, "--resume", resume)
	}
	if strings.TrimSpace(p.Model) != "" {
		args = append(args, "--model", p.Model)
	}
	doNarrate := c.cfg.NarrateOn
	if p.Narrate != nil {
		doNarrate = *p.Narrate
	}
	if doNarrate {
		args = append(args, "--append-system-prompt", narrateSystem)
	}
	args = append(args, "--permission-mode", c.cfg.Perm, p.Text)
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(c.cfg.Timeout)*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "claude", args...)
	cmd.Dir = dir
	stdout, err := cmd.StdoutPipe()
	if err != nil || cmd.Start() != nil {
		emit(model.Event{T: "reply", Text: "Failed to start agent."})
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
		case line, more := <-lines:
			if !more {
				scanning = false
				break
			}
			var ev model.StreamEvent
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
					emit(model.Event{T: "model", Name: name})
				}
			}
			for _, e := range transformEvent(ev) {
				if e.T == "reply" {
					sawReply = true
				}
				emit(e)
			}
		case <-heartbeat.C:
			emit(model.Event{T: "working", Text: working[hbn%len(working)]})
			hbn++
		}
	}
	cmd.Wait()
	if ctx.Err() == context.DeadlineExceeded {
		emit(model.Event{T: "reply", Text: fmt.Sprintf("The agent took longer than %d seconds, so I stopped it. Try a smaller step.", c.cfg.Timeout)})
	} else if !sawReply {
		emit(model.Event{T: "reply", Text: "No response."})
	}
	if newSession != "" {
		c.agents.SetSession(id, newSession)
	}
}
