package models

// SessionInfo is one entry in the GET /sessions response.
type SessionInfo struct {
	ID      string `json:"id"`
	Preview string `json:"preview"`
	Mtime   int64  `json:"mtime"`
}
