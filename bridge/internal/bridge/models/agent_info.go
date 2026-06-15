package models

// AgentInfo is one entry in the GET /agents response.
type AgentInfo struct {
	ID     int     `json:"id"`
	Dir    string  `json:"dir"`
	Name   string  `json:"name"`
	Branch *string `json:"branch"`
	Dirty  bool    `json:"dirty"`
	Exists bool    `json:"exists"`
}
