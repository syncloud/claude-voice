package bridge

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
)

// Piper synthesizes speech via the piper CLI and manages its voice models.
type Piper struct {
	bin    string
	lib    string
	espeak string
	voices string
	model  string
	fs     *FS
}

// NewPiper wires the piper binary/voice paths from cfg and the filesystem.
func NewPiper(cfg Config, fs *FS) *Piper {
	return &Piper{
		bin:    cfg.PiperBin,
		lib:    cfg.PiperLib,
		espeak: cfg.PiperEspeak,
		voices: cfg.PiperVoices,
		model:  cfg.PiperModel,
		fs:     fs,
	}
}

// Enabled reports whether the piper binary is present.
func (p *Piper) Enabled() bool { return p.fs.Exists(p.bin) }

// Voices returns the names of the .onnx voices available to piper, sorted.
func (p *Piper) Voices() []string {
	entries, err := p.fs.ReadDir(p.voices)
	if err != nil {
		return []string{}
	}
	out := []string{}
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".onnx") {
			out = append(out, strings.TrimSuffix(e.Name(), ".onnx"))
		}
	}
	sort.Strings(out)
	return out
}

// resolveVoice picks the voice model file: the named voice if it exists, else
// the explicit configured model, else the first available voice.
func (p *Piper) resolveVoice(name string) string {
	if name != "" {
		if f := filepath.Join(p.voices, name+".onnx"); p.fs.Exists(f) {
			return f
		}
	}
	if p.model != "" && p.fs.Exists(p.model) {
		return p.model
	}
	for _, v := range p.Voices() {
		return filepath.Join(p.voices, v+".onnx")
	}
	return ""
}

// Synth renders text to a WAV byte slice using the named (or default) voice.
func (p *Piper) Synth(text, voice string) ([]byte, error) {
	model := p.resolveVoice(voice)
	if model == "" {
		return nil, fmt.Errorf("no voice model")
	}
	d, err := p.fs.TempDir("tts")
	if err != nil {
		return nil, err
	}
	defer p.fs.RemoveAll(d)
	wav := filepath.Join(d, "o.wav")
	cmd := exec.Command("grun", p.bin, "-m", model, "--espeak_data", p.espeak, "-f", wav)
	cmd.Env = append(os.Environ(), "LD_LIBRARY_PATH="+p.lib)
	cmd.Stdin = strings.NewReader(text)
	if err := cmd.Run(); err != nil {
		return nil, err
	}
	return p.fs.ReadFile(wav)
}
