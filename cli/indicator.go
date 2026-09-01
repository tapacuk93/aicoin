package main

import (
	"fmt"
	"os"
	"strings"
	"sync"
	"time"
)

// What a call is costing, shown while it happens and after it finishes.
//
// A consortium call runs for minutes and spends real coins the whole time. Without a running
// indicator there is nothing on screen between "asking" and the answer, and no way to tell a slow
// panel from a hung one; without the balance either side of it, the price of what just happened is
// invisible until the next `aicoin show`.

// isTTY reports whether stderr is a terminal. Everything here is decoration: piped or redirected,
// it must not appear, or it ends up in whatever file the caller was collecting.
func isTTY() bool {
	info, err := os.Stderr.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}

var spinnerFrames = []string{"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"}

// spinner is a single rewritten line on stderr: a frame, a label, and how long this has been going.
type spinner struct {
	stop chan struct{}
	done chan struct{}
	mu   sync.Mutex
	text string
}

// startSpinner begins animating, or returns a no-op spinner when stderr is not a terminal.
func startSpinner(label string) *spinner {
	s := &spinner{stop: make(chan struct{}), done: make(chan struct{}), text: label}
	if !isTTY() {
		close(s.done)
		return s
	}
	go func() {
		defer close(s.done)
		started := time.Now()
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()
		frame := 0
		for {
			select {
			case <-s.stop:
				fmt.Fprint(os.Stderr, "\r\033[K")
				return
			case <-ticker.C:
				s.mu.Lock()
				text := s.text
				s.mu.Unlock()
				fmt.Fprintf(os.Stderr, "\r\033[K%s %s  %s",
					spinnerFrames[frame%len(spinnerFrames)], text, elapsed(time.Since(started)))
				frame++
			}
		}
	}()
	return s
}

// update changes the label without restarting the clock.
func (s *spinner) update(text string) {
	s.mu.Lock()
	s.text = text
	s.mu.Unlock()
}

// finish clears the line. Safe to call more than once.
func (s *spinner) finish() {
	select {
	case <-s.done:
		return
	default:
	}
	close(s.stop)
	<-s.done
}

func elapsed(d time.Duration) string {
	seconds := int(d.Seconds())
	if seconds < 60 {
		return fmt.Sprintf("%ds", seconds)
	}
	return fmt.Sprintf("%dm%02ds", seconds/60, seconds%60)
}

// coinBar renders what a call cost against what the wallet had, e.g.
//
//	◆◆◆◆◆◇◇◇◇◇  15 aicoin spent · 27 left
//
// The bar is the spent share of what was there when the call started, which is the thing worth
// seeing at a glance: a call that took a third of the wallet looks like a call that took a third of
// the wallet.
func coinBar(before, after float64, charged int64) string {
	spent := float64(charged)
	if spent <= 0 {
		spent = before - after
	}
	line := fmt.Sprintf("%s aicoin spent · %s left", formatCoins(spent), formatCoins(after))
	if before <= 0 || spent <= 0 {
		return line
	}
	const width = 10
	filled := int((spent/before)*width + 0.5)
	if filled < 1 {
		filled = 1
	}
	if filled > width {
		filled = width
	}
	return strings.Repeat("◆", filled) + strings.Repeat("◇", width-filled) + "  " + line
}
