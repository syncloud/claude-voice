package models

// EditOp is one old/new replacement within a MultiEdit tool call.
type EditOp struct {
	OldString string `json:"old_string"`
	NewString string `json:"new_string"`
}
