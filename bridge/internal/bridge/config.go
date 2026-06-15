package bridge

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Config holds all runtime settings for the bridge. Defaults come from the
// environment (DefaultConfig); the CLI layer may override any field.
type Config struct {
	Whisper string
	Model   string
	Perm    string
	Timeout int
	Host    string
	Port    string

	PiperBin    string
	PiperLib    string
	PiperEspeak string
	PiperVoices string
	PiperModel  string

	NarrateOn bool

	WorkDir string
}

// DefaultConfig builds a Config from environment variables, matching the
// historical behaviour of the bridge.
func DefaultConfig() Config {
	piperBin := env("PIPER_BIN", expand("~/piper/piper"))
	return Config{
		Whisper: env("WHISPER_BIN", expand("~/storage/projects/whisper.cpp/build/bin/whisper-cli")),
		Model:   env("WHISPER_MODEL", expand("~/whisper-models/ggml-base.en.bin")),
		Perm:    env("VOICE_PERM", "bypassPermissions"),
		Timeout: envInt("VOICE_TIMEOUT", 1800),
		Host:    env("VOICE_HOST", "127.0.0.1"),
		Port:    env("VOICE_PORT", "8765"),

		PiperBin:    piperBin,
		PiperLib:    env("PIPER_LIB", filepath.Dir(piperBin)),
		PiperEspeak: env("PIPER_ESPEAK", filepath.Join(filepath.Dir(piperBin), "espeak-ng-data")),
		PiperVoices: env("PIPER_VOICES", expand("~/piper-voices")),
		PiperModel:  env("PIPER_MODEL", ""),

		NarrateOn: env("VOICE_NARRATE", "1") != "0",

		WorkDir: env("VOICE_WORKDIR", ""),
	}
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func envInt(k string, d int) int {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return d
}

func expand(p string) string {
	if strings.HasPrefix(p, "~/") {
		if h, err := os.UserHomeDir(); err == nil {
			return filepath.Join(h, p[2:])
		}
	}
	return p
}

func home() string {
	if h, err := os.UserHomeDir(); err == nil {
		return h
	}
	return "/"
}

// Home is the exported home-directory helper for the CLI layer.
func Home() string { return home() }
