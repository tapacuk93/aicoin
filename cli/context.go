package main

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode/utf8"
)

// Directory context: what the panel is told about the directory the command was run in.
//
// A question asked in a project is usually about that project, and a panel that cannot see the
// files can only answer it in general terms. So a listing of the working directory goes into every
// consortium call by default — it is small, and it is the difference between "here is how one
// usually structures a Go CLI" and an answer about this one. File *contents* are opt-in (`-f`),
// because they are neither small nor free: the shared record goes to every panelist on every round
// and every character of it is billed as input.

// Directories that are never worth sending: build output, dependency trees and version-control
// internals. Their contents are derived, enormous, or both.
var skipDirs = map[string]bool{
	".git": true, ".gradle": true, ".idea": true, ".vscode": true, ".venv": true,
	"node_modules": true, "vendor": true, "build": true, "dist": true, "target": true,
	"__pycache__": true, ".mypy_cache": true, ".pytest_cache": true, ".next": true,
	"DerivedData": true, ".terraform": true,
}

// Extensions that are certainly not text. Sniffing catches the rest; this just avoids reading them.
var binaryExts = map[string]bool{
	".png": true, ".jpg": true, ".jpeg": true, ".gif": true, ".webp": true, ".ico": true,
	".pdf": true, ".zip": true, ".gz": true, ".tar": true, ".jar": true, ".class": true,
	".so": true, ".dylib": true, ".dll": true, ".exe": true, ".bin": true, ".o": true, ".a": true,
	".mp3": true, ".mp4": true, ".mov": true, ".wav": true, ".woff": true, ".woff2": true,
	".ttf": true, ".otf": true, ".pem": true, ".p8": true, ".der": true,
}

const (
	maxListedFiles = 300
	maxFileBytes   = 256 * 1024
)

// dirContext is what was gathered, and what it cost.
type dirContext struct {
	Text      string
	FileCount int // files whose contents were included
	Listed    int // files named in the listing
	Chars     int
	Truncated bool
	Root      string
}

// gatherDir builds the directory block: a listing of the tree, then the contents of any file
// matching one of `includes` (shell globs, matched against the path relative to root), up to
// `budget` characters in total.
func gatherDir(root string, includes []string, budget int) (*dirContext, error) {
	absolute, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	var paths []string
	err = filepath.WalkDir(absolute, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return nil // an unreadable corner of the tree is not worth failing the call over
		}
		name := entry.Name()
		if entry.IsDir() {
			if path != absolute && (skipDirs[name] || strings.HasPrefix(name, ".") && name != ".") {
				return filepath.SkipDir
			}
			return nil
		}
		if strings.HasPrefix(name, ".") || binaryExts[strings.ToLower(filepath.Ext(name))] {
			return nil
		}
		relative, relErr := filepath.Rel(absolute, path)
		if relErr != nil {
			return nil
		}
		paths = append(paths, relative)
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Strings(paths)

	result := &dirContext{Root: absolute}
	var out strings.Builder
	out.WriteString("Working directory: " + absolute + "\n\nFiles:\n")
	listed := paths
	if len(listed) > maxListedFiles {
		listed = listed[:maxListedFiles]
		result.Truncated = true
	}
	for _, path := range listed {
		out.WriteString("  " + path + "\n")
	}
	if result.Truncated {
		out.WriteString(fmt.Sprintf("  ... and %d more\n", len(paths)-len(listed)))
	}
	result.Listed = len(listed)

	if len(includes) > 0 {
		for _, path := range paths {
			if !matchesAny(path, includes) {
				continue
			}
			body, ok := readTextFile(filepath.Join(absolute, path))
			if !ok {
				continue
			}
			block := "\n--- " + path + " ---\n" + body + "\n"
			if out.Len()+len(block) > budget {
				// Stop at a file boundary rather than mid-file: half a source file in the record
				// invites confident answers about code the panel cannot see the end of.
				out.WriteString("\n[remaining files omitted: the context budget is full]\n")
				result.Truncated = true
				break
			}
			out.WriteString(block)
			result.FileCount++
		}
	}

	text := out.String()
	if len(text) > budget {
		text = text[:budget] + "\n[truncated]\n"
		result.Truncated = true
	}
	result.Text = text
	result.Chars = len(text)
	return result, nil
}

// matchesAny reports whether a relative path matches one of the given globs. A bare name matches
// anywhere in the tree ("main.go" finds "cli/main.go"), since that is what a person typing one
// means; a pattern containing a separator is matched against the whole path.
func matchesAny(path string, patterns []string) bool {
	for _, pattern := range patterns {
		if pattern == "" {
			continue
		}
		if strings.Contains(pattern, string(filepath.Separator)) {
			if ok, _ := filepath.Match(pattern, path); ok {
				return true
			}
			// A directory prefix means everything under it.
			if strings.HasPrefix(path, strings.TrimSuffix(pattern, "/")+"/") {
				return true
			}
			continue
		}
		if ok, _ := filepath.Match(pattern, filepath.Base(path)); ok {
			return true
		}
	}
	return false
}

// readTextFile returns a file's contents, or false when it is too big or is not text. Binary in the
// record wastes budget and tells the panel nothing.
func readTextFile(path string) (string, bool) {
	info, err := os.Stat(path)
	if err != nil || info.Size() > maxFileBytes {
		return "", false
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", false
	}
	if !utf8.Valid(data) {
		return "", false
	}
	for _, b := range data {
		if b == 0 {
			return "", false
		}
	}
	return string(data), true
}
