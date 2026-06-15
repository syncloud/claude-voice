package models

// TokenUsage is the token accounting attached to claude stream events.
type TokenUsage struct {
	InputTokens              int `json:"input_tokens"`
	OutputTokens             int `json:"output_tokens"`
	CacheReadInputTokens     int `json:"cache_read_input_tokens"`
	CacheCreationInputTokens int `json:"cache_creation_input_tokens"`
}

// InOut returns total input (including cache) and output tokens.
func (u *TokenUsage) InOut() (int, int) {
	if u == nil {
		return 0, 0
	}
	return u.InputTokens + u.CacheReadInputTokens + u.CacheCreationInputTokens, u.OutputTokens
}
