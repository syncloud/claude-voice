package models

// ToolInput is the union of tool-call input fields we surface to the app.
type ToolInput struct {
	Command     string   `json:"command"`
	FilePath    string   `json:"file_path"`
	Pattern     string   `json:"pattern"`
	URL         string   `json:"url"`
	Query       string   `json:"query"`
	Description string   `json:"description"`
	OldString   string   `json:"old_string"`
	NewString   string   `json:"new_string"`
	Content     string   `json:"content"`
	Edits       []EditOp `json:"edits"`
}
