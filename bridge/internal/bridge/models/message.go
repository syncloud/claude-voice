package models

import "encoding/json"

// Message is the claude message envelope inside a StreamEvent.
type Message struct {
	Model   string          `json:"model"`
	Content json.RawMessage `json:"content"`
	Usage   *TokenUsage     `json:"usage"`
}

// TextContent returns the message content when it is a plain string (user
// turns), or "" when it is a structured block array.
func (m *Message) TextContent() string {
	if m == nil {
		return ""
	}
	var s string
	if json.Unmarshal(m.Content, &s) == nil {
		return s
	}
	return ""
}

// Blocks returns the message content when it is an array of typed blocks.
func (m *Message) Blocks() []ContentBlock {
	if m == nil {
		return nil
	}
	var b []ContentBlock
	json.Unmarshal(m.Content, &b)
	return b
}
