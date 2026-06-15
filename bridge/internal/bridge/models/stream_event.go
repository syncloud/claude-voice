package models

// StreamEvent models the claude CLI stream-json lines and the session .jsonl
// log entries we read back.
type StreamEvent struct {
	Type       string                    `json:"type"`
	SessionID  string                    `json:"session_id"`
	Model      string                    `json:"model"`
	Result     string                    `json:"result"`
	Message    *Message                  `json:"message"`
	Usage      *TokenUsage               `json:"usage"`
	ModelUsage map[string]ModelUsageInfo `json:"modelUsage"`
}
