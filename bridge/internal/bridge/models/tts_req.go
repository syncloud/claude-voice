package models

// TtsReq is the POST /tts request body.
type TtsReq struct {
	Text  string `json:"text"`
	Voice string `json:"voice"`
}
