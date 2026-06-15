package bridge

import (
	"path/filepath"
	"strings"

	"github.com/cyberb/claude-voice/bridge/internal/bridge/models"
)

const narrateMarker = "===SPOKEN==="

func intp(v int) *int { return &v }

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
