package models

// DirListing is the GET /ls response.
type DirListing struct {
	Dir    string   `json:"dir"`
	Parent *string  `json:"parent"`
	Dirs   []string `json:"dirs"`
}
