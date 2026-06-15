package models

// ContentBlock is one typed block of a structured message content array.
type ContentBlock struct {
	Type  string    `json:"type"`
	Text  string    `json:"text"`
	Name  string    `json:"name"`
	Input ToolInput `json:"input"`
}
