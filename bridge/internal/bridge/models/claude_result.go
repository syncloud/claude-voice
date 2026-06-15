package models

// ClaudeResult is the single-object output of `claude -p --output-format json`.
type ClaudeResult struct {
	Result    string `json:"result"`
	SessionID string `json:"session_id"`
}
