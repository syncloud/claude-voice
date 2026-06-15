package bridge

import (
	"os/exec"
	"path/filepath"
	"strings"
)

// Whisper transcribes recorded audio to text via the whisper.cpp CLI.
type Whisper struct {
	bin   string
	model string
	fs    *FS
}

// NewWhisper wires the whisper binary/model paths from cfg and the filesystem.
func NewWhisper(cfg Config, fs *FS) *Whisper {
	return &Whisper{bin: cfg.Whisper, model: cfg.Model, fs: fs}
}

// Transcribe writes wav to a temp file, runs whisper, and returns cleaned text.
func (w *Whisper) Transcribe(wav []byte) string {
	d, err := w.fs.TempDir("cv")
	if err != nil {
		return ""
	}
	defer w.fs.RemoveAll(d)
	in := filepath.Join(d, "in.wav")
	outp := filepath.Join(d, "out")
	if err := w.fs.WriteFile(in, wav); err != nil {
		return ""
	}
	exec.Command(w.bin, "-m", w.model, "-f", in, "-l", "en", "-nt", "-np", "-otxt", "-of", outp).Run()
	txt, err := w.fs.ReadFile(outp + ".txt")
	if err != nil {
		return ""
	}
	s := string(txt)
	for _, junk := range []string{"[BLANK_AUDIO]", "(silence)"} {
		s = strings.ReplaceAll(s, junk, "")
	}
	return strings.Join(strings.Fields(s), " ")
}
