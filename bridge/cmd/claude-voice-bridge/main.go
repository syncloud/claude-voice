package main

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
	"github.com/syncloud/claude-voice/bridge/internal/bridge"
)

func main() {
	cfg := bridge.DefaultConfig()

	root := &cobra.Command{
		Use:          "claude-voice-bridge",
		Short:        "Termux-side localhost bridge for the claude-voice Android app",
		SilenceUsage: true,
		RunE: func(cmd *cobra.Command, args []string) error {
			return run(cfg)
		},
	}

	f := root.Flags()
	f.StringVar(&cfg.Host, "host", cfg.Host, "listen host")
	f.StringVar(&cfg.Port, "port", cfg.Port, "listen port")
	f.StringVar(&cfg.Perm, "perm", cfg.Perm, "claude --permission-mode")
	f.IntVar(&cfg.Timeout, "timeout", cfg.Timeout, "agent timeout in seconds")
	f.StringVar(&cfg.Whisper, "whisper", cfg.Whisper, "whisper-cli binary path")
	f.StringVar(&cfg.Model, "whisper-model", cfg.Model, "whisper model path")
	f.StringVar(&cfg.WorkDir, "workdir", cfg.WorkDir, "starting agent directory (default: home)")
	f.BoolVar(&cfg.NarrateOn, "narrate", cfg.NarrateOn, "narration on by default")
	f.IntVar(&cfg.CompactAt, "compact-at", cfg.CompactAt, "auto-compact when context reaches this percent (0 disables)")
	f.StringVar(&cfg.PiperBin, "piper-bin", cfg.PiperBin, "piper binary path")
	f.StringVar(&cfg.PiperLib, "piper-lib", cfg.PiperLib, "piper LD_LIBRARY_PATH dir")
	f.StringVar(&cfg.PiperEspeak, "piper-espeak", cfg.PiperEspeak, "piper espeak-ng-data dir")
	f.StringVar(&cfg.PiperVoices, "piper-voices", cfg.PiperVoices, "piper voices dir")
	f.StringVar(&cfg.PiperModel, "piper-model", cfg.PiperModel, "explicit piper voice model")

	if err := root.Execute(); err != nil {
		os.Exit(1)
	}
}

func run(cfg bridge.Config) error {
	start := cfg.WorkDir
	if start == "" {
		start = bridge.Home()
	}
	fs := bridge.NewFS()
	agents := bridge.NewAgents(bridge.NewGit(), fs)
	agents.Add(start)
	claude := bridge.NewClaude(cfg, agents)
	whisper := bridge.NewWhisper(cfg, fs)
	piper := bridge.NewPiper(cfg, fs)

	addr := cfg.Host + ":" + cfg.Port
	fmt.Printf("claude-voice bridge on http://%s  (perm=%s, start_dir=%s)\n", addr, cfg.Perm, start)

	srv := bridge.NewServer(addr, agents, claude, whisper, piper, fs)
	if err := srv.ListenAndServe(); err != nil {
		fmt.Fprintln(os.Stderr, "server error:", err)
		return err
	}
	return nil
}
