package bridge

import (
	"os/exec"
	"strings"
)

// Git answers branch and dirty status by shelling out to the git CLI.
type Git struct{}

// NewGit returns a Git backed by the git command on PATH.
func NewGit() *Git { return &Git{} }

// Info returns the current branch of dir and whether its working tree is dirty.
func (*Git) Info(dir string) (string, bool) {
	b, err := exec.Command("git", "-C", dir, "rev-parse", "--abbrev-ref", "HEAD").Output()
	if err != nil {
		return "", false
	}
	branch := strings.TrimSpace(string(b))
	s, _ := exec.Command("git", "-C", dir, "status", "--porcelain").Output()
	return branch, len(strings.TrimSpace(string(s))) > 0
}
