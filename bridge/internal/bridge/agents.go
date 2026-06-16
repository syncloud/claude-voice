package bridge

import (
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"github.com/syncloud/claude-voice/bridge/internal/bridge/model"
)

type agent struct {
	dir     string
	session string
}

// Agents is the in-memory registry of working directories the app drives. It
// enriches each entry with git/filesystem status when listing.
type Agents struct {
	git    *Git
	fs     *FS
	mu     sync.Mutex
	m      map[int]*agent
	nextID int
}

// NewAgents creates an empty registry that resolves status via git and fs.
func NewAgents(git *Git, fs *FS) *Agents {
	return &Agents{git: git, fs: fs, m: map[int]*agent{}, nextID: 1}
}

// Add registers (or returns the existing id for) a working directory.
func (a *Agents) Add(dir string) int {
	dir = expand(strings.TrimSpace(dir))
	if abs, err := filepath.Abs(dir); err == nil {
		dir = abs
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	for id, ag := range a.m {
		if ag.dir == dir {
			return id
		}
	}
	id := a.nextID
	a.nextID++
	a.m[id] = &agent{dir: dir}
	return id
}

// SetSession records the claude session id for an agent (no-op if unknown).
func (a *Agents) SetSession(id int, session string) {
	a.mu.Lock()
	if ag := a.m[id]; ag != nil {
		ag.session = session
	}
	a.mu.Unlock()
}

// Delete removes an agent from the registry.
func (a *Agents) Delete(id int) {
	a.mu.Lock()
	delete(a.m, id)
	a.mu.Unlock()
}

// lookup returns the working dir and current session of an agent.
func (a *Agents) lookup(id int) (dir, session string, ok bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if ag := a.m[id]; ag != nil {
		return ag.dir, ag.session, true
	}
	return "", "", false
}

// List returns every agent with current git/exists status, ordered by id.
func (a *Agents) List() []model.AgentInfo {
	a.mu.Lock()
	dirs := map[int]string{}
	ids := make([]int, 0, len(a.m))
	for id, ag := range a.m {
		ids = append(ids, id)
		dirs[id] = ag.dir
	}
	a.mu.Unlock()
	sort.Ints(ids)
	out := []model.AgentInfo{}
	for _, id := range ids {
		dir := dirs[id]
		branch, dirty := a.git.Info(dir)
		var bp *string
		if branch != "" {
			b := branch
			bp = &b
		}
		name := filepath.Base(dir)
		if name == "" || name == string(filepath.Separator) {
			name = dir
		}
		out = append(out, model.AgentInfo{ID: id, Dir: dir, Name: name, Branch: bp, Dirty: dirty, Exists: a.fs.Exists(dir)})
	}
	return out
}
