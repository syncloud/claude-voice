package models

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
