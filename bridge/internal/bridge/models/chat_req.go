package models

// ChatReq is the POST /chat request body.
type ChatReq struct {
	Text    string `json:"text"`
	Agent   *int   `json:"agent"`
	Narrate *bool  `json:"narrate"`
	Model   string `json:"model"`
}
