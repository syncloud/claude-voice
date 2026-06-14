package bridge

import "encoding/json"

// Event is a single typed message the bridge streams to the app (NDJSON over
// /chat, and the JSON array returned by /history). Empty fields are omitted.
type Event struct {
	T      string `json:"t"`
	Text   string `json:"text,omitempty"`
	Label  string `json:"label,omitempty"`
	File   string `json:"file,omitempty"`
	Patch  string `json:"patch,omitempty"`
	Speech string `json:"speech,omitempty"`
	Name   string `json:"name,omitempty"`
	In     *int   `json:"in,omitempty"`
	Out    *int   `json:"out,omitempty"`
	Max    *int   `json:"max,omitempty"`
}

func intp(v int) *int { return &v }

// streamEvent models the claude CLI stream-json lines and the session .jsonl
// log entries we read back.
type streamEvent struct {
	Type       string                    `json:"type"`
	SessionID  string                    `json:"session_id"`
	Model      string                    `json:"model"`
	Result     string                    `json:"result"`
	Message    *message                  `json:"message"`
	Usage      *tokenUsage               `json:"usage"`
	ModelUsage map[string]modelUsageInfo `json:"modelUsage"`
}

type message struct {
	Model   string          `json:"model"`
	Content json.RawMessage `json:"content"`
	Usage   *tokenUsage     `json:"usage"`
}

// textContent returns the message content when it is a plain string (user
// turns), or "" when it is a structured block array.
func (m *message) textContent() string {
	if m == nil {
		return ""
	}
	var s string
	if json.Unmarshal(m.Content, &s) == nil {
		return s
	}
	return ""
}

// blocks returns the message content when it is an array of typed blocks.
func (m *message) blocks() []contentBlock {
	if m == nil {
		return nil
	}
	var b []contentBlock
	json.Unmarshal(m.Content, &b)
	return b
}

type contentBlock struct {
	Type  string    `json:"type"`
	Text  string    `json:"text"`
	Name  string    `json:"name"`
	Input toolInput `json:"input"`
}

type toolInput struct {
	Command     string   `json:"command"`
	FilePath    string   `json:"file_path"`
	Pattern     string   `json:"pattern"`
	URL         string   `json:"url"`
	Query       string   `json:"query"`
	Description string   `json:"description"`
	OldString   string   `json:"old_string"`
	NewString   string   `json:"new_string"`
	Content     string   `json:"content"`
	Edits       []editOp `json:"edits"`
}

type editOp struct {
	OldString string `json:"old_string"`
	NewString string `json:"new_string"`
}

type tokenUsage struct {
	InputTokens              int `json:"input_tokens"`
	OutputTokens             int `json:"output_tokens"`
	CacheReadInputTokens     int `json:"cache_read_input_tokens"`
	CacheCreationInputTokens int `json:"cache_creation_input_tokens"`
}

func (u *tokenUsage) inOut() (int, int) {
	if u == nil {
		return 0, 0
	}
	return u.InputTokens + u.CacheReadInputTokens + u.CacheCreationInputTokens, u.OutputTokens
}

type modelUsageInfo struct {
	ContextWindow int `json:"contextWindow"`
}

// claudeResult is the single-object output of `claude -p --output-format json`.
type claudeResult struct {
	Result    string `json:"result"`
	SessionID string `json:"session_id"`
}

// Request bodies.
type agentReq struct {
	Dir     string `json:"dir"`
	Session string `json:"session"`
}

type ttsReq struct {
	Text  string `json:"text"`
	Voice string `json:"voice"`
}

type narrateReq struct {
	Text string `json:"text"`
}

type chatReq struct {
	Text    string `json:"text"`
	Agent   *int   `json:"agent"`
	Narrate *bool  `json:"narrate"`
	Model   string `json:"model"`
}

// Response bodies.
type agentInfo struct {
	ID     int     `json:"id"`
	Dir    string  `json:"dir"`
	Name   string  `json:"name"`
	Branch *string `json:"branch"`
	Dirty  bool    `json:"dirty"`
	Exists bool    `json:"exists"`
}

type sessionInfo struct {
	ID      string `json:"id"`
	Preview string `json:"preview"`
	Mtime   int64  `json:"mtime"`
}

type dirListing struct {
	Dir    string   `json:"dir"`
	Parent *string  `json:"parent"`
	Dirs   []string `json:"dirs"`
}

type compactResp struct {
	Summary string `json:"summary"`
}
