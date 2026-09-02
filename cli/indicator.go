package main

import (
	"fmt"
	"os"
	"strings"
	"sync"
	"time"
)

// What is on screen while a call runs.
//
// A consortium takes minutes and spends coins the whole time, so the useful thing to watch is not
// that something is happening — it plainly is — but what it is costing. The line shows the wallet,
// live: the balance is polled while the call runs and drops as each turn settles, which is both the
// progress indicator and the price tag.

// isTTY reports whether stderr is a terminal. Everything here is decoration: piped or redirected,
// it must not appear, or it ends up in whatever file the caller was collecting.
func isTTY() bool {
	info, err := os.Stderr.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}

// stdinIsTTY reports whether there is a person at the keyboard to answer a prompt. Piped input has
// no one to ask, and reading a confirmation from the pipe would eat the next question.
func stdinIsTTY() bool {
	info, err := os.Stdin.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}

// liveLine is a single line on stderr that is rewritten in place until it is finished.
type liveLine struct {
	stop chan struct{}
	done chan struct{}
	mu   sync.Mutex
	text string
}

func startLine(initial string) *liveLine {
	line := &liveLine{stop: make(chan struct{}), done: make(chan struct{}), text: initial}
	if !isTTY() {
		close(line.done)
		return line
	}
	go func() {
		defer close(line.done)
		ticker := time.NewTicker(200 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-line.stop:
				fmt.Fprint(os.Stderr, "\r\033[K")
				return
			case <-ticker.C:
				line.mu.Lock()
				text := line.text
				line.mu.Unlock()
				fmt.Fprintf(os.Stderr, "\r\033[K%s", text)
			}
		}
	}()
	return line
}

func (l *liveLine) set(text string) {
	l.mu.Lock()
	l.text = text
	l.mu.Unlock()
}

// finish clears the line. Safe to call more than once.
func (l *liveLine) finish() {
	select {
	case <-l.done:
		return
	default:
	}
	close(l.stop)
	<-l.done
}

// coinMeterText renders the wallet as it stands: the balance, and what has gone since the call
// started once anything has.
func coinMeterText(current, start float64) string {
	line := formatCoins(current) + " aicoin"
	if spent := start - current; spent > 0 {
		line += "  −" + formatCoins(spent)
	}
	return line
}

// startCoinMeter shows the wallet's balance while a call runs, polling it as the call spends.
//
// The poll is a plain unauthenticated balance read — free, and the same one `aicoin balance` makes.
// Every few seconds is enough: a consortium settles a turn at a time, and a number that flickers
// faster than the eye is not more informative.
func startCoinMeter(client *Client, address string, start float64) *liveLine {
	line := startLine(coinMeterText(start, start))
	if !isTTY() {
		return line
	}
	go func() {
		ticker := time.NewTicker(2500 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-line.stop:
				return
			case <-ticker.C:
				// A failed read leaves the last figure standing: a blank line, or a zero, would
				// say something untrue about the wallet.
				if balance, err := client.balance(address); err == nil {
					line.set(coinMeterText(balance, start))
				}
			}
		}
	}()
	return line
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
