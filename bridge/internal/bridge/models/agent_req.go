package models

// AgentReq is the POST /agents request body.
type AgentReq struct {
	Dir     string `json:"dir"`
	Session string `json:"session"`
}
