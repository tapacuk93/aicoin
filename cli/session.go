package main

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// An interactive session: `aicoin .` opens one on the current directory, and every line typed into
// it is a consortium call that can see the files there.
//
// The difference from a one-shot call is memory. Each turn carries what was already asked and
// answered, so "and what about the second one?" means something — the panel is given the session's
// exchanges as part of the same shared record it gets the directory in. That record is re-gathered
// every turn, so a file edited between two questions is seen as it is now, not as it was when the
// session opened.

const (
	// How much of the conversation to carry. The whole record — directory, conversation, drafts,
	// reviews — is sent to every panelist on every round, so history is not free; the newest
	// exchanges are the ones a follow-up question is about.
	sessionHistoryBudget = 12000
	sessionBanner        = `aicoin — every question goes to the whole panel, which then reviews its own answer.
Type a question, or /help for what else. Ctrl-D to leave.`
)

// exchange is one question and the answer the panel settled on.
type exchange struct {
	Question string
	Answer   string
}

// sessionState is everything a session carries between turns.
type sessionState struct {
	dir       string
	includes  stringList
	providers string
	editor    string
	rounds    int
	budget    int
	verbose   bool
	history   []exchange
	spent     int64
	turns     int
}

// history renders the conversation so far, newest-biased: when it does not fit, the oldest
// exchanges are dropped and their absence is stated, exactly as the proxy trims its own record.
func (s *sessionState) historyText() string {
	if len(s.history) == 0 {
		return ""
	}
	var blocks []string
	total := 0
	dropped := 0
	for i := len(s.history) - 1; i >= 0; i-- {
		block := "You asked:\n" + s.history[i].Question + "\n\nThe panel answered:\n" + s.history[i].Answer + "\n"
		if total+len(block) > sessionHistoryBudget && len(blocks) > 0 {
			dropped = i + 1
			break
		}
		total += len(block)
		blocks = append([]string{block}, blocks...)
	}
	out := "=== Earlier in this session ===\n"
	if dropped > 0 {
		out += fmt.Sprintf("[%d earlier exchange(s) omitted]\n\n", dropped)
	}
	return out + strings.Join(blocks, "\n")
}

func runSession(dir string, url string, walletPath string, state *sessionState) error {
	absolute, err := filepath.Abs(dir)
	if err != nil {
		return err
	}
	info, err := os.Stat(absolute)
	if err != nil || !info.IsDir() {
		return fmt.Errorf("%s is not a directory", dir)
	}
	state.dir = absolute

	wallet, err := loadWallet(walletPath)
	if err != nil {
		return err
	}
	client := newClient(url, 30*time.Minute)

	fmt.Fprintln(os.Stderr, sessionBanner)
	fmt.Fprintf(os.Stderr, "directory %s\n", absolute)
	balance, balanceErr := client.balance(wallet.Address)
	if balanceErr == nil {
		fmt.Fprintf(os.Stderr, "wallet %s… · %s aicoin\n", wallet.Address[:12], formatCoins(balance))
	} else {
		fmt.Fprintf(os.Stderr, "wallet %s… · balance unknown (%v)\n", wallet.Address[:12], balanceErr)
	}

	reader := bufio.NewReader(os.Stdin)
	for {
		fmt.Fprint(os.Stderr, "\naicoin ▸ ")
		line, err := reader.ReadString('\n')
		if err == io.EOF {
			fmt.Fprintln(os.Stderr)
			break
		}
		if err != nil {
			return err
		}
		question := strings.TrimSpace(line)
		if question == "" {
			continue
		}
		if strings.HasPrefix(question, "/") {
			stop, err := state.command(question, client, wallet)
			if err != nil {
				fmt.Fprintln(os.Stderr, "aicoin: "+err.Error())
			}
			if stop {
				break
			}
			continue
		}
		if err := state.askPanel(question, client, wallet); err != nil {
			// One failed turn does not end the session: a provider hiccup or an empty wallet is
			// something the next question, or a claim, can recover from.
			fmt.Fprintln(os.Stderr, "aicoin: "+err.Error())
		}
	}
	if state.turns > 0 {
		fmt.Fprintf(os.Stderr, "%d question(s), %s aicoin this session\n",
			state.turns, formatCoins(float64(state.spent)))
	}
	return nil
}

// askPanel runs one consortium turn and prints its answer.
func (s *sessionState) askPanel(question string, client *Client, wallet *Wallet) error {
	// Re-gathered every turn: a file edited mid-session should be seen as it is now.
	gathered, err := gatherDir(s.dir, s.includes, s.budget)
	if err != nil {
		return err
	}
	background := gathered.Text
	if history := s.historyText(); history != "" {
		background = strings.TrimSpace(background + "\n\n" + history)
	}

	request := map[string]any{"prompt": question, "context": background}
	if s.providers != "" {
		var panel []string
		for _, name := range strings.Split(s.providers, ",") {
			if trimmed := strings.TrimSpace(name); trimmed != "" {
				panel = append(panel, trimmed)
			}
		}
		request["providers"] = panel
	}
	if s.editor != "" {
		request["editor"] = s.editor
	}
	if s.rounds > 0 {
		request["max_rounds"] = s.rounds
	}
	body, err := jsonBytes(request)
	if err != nil {
		return err
	}

	balanceBefore, balanceErr := client.balance(wallet.Address)
	spin := startSpinner("asking the panel · every turn is a paid call")
	responseBody, _, err := client.withToken(wallet, "POST", "/consortium", body, nil)
	spin.finish()
	if err != nil {
		return err
	}
	parsed, err := parseConsortium(responseBody)
	if err != nil {
		fmt.Println(strings.TrimSpace(string(responseBody)))
		return nil
	}

	fmt.Println(parsed.Answer)
	if s.verbose {
		for _, review := range parsed.Reviews {
			if review.Clean {
				fmt.Fprintf(os.Stderr, "\nround %d — %s: no comments\n", review.Round, review.Provider)
				continue
			}
			fmt.Fprintf(os.Stderr, "\nround %d — %s:\n%s\n", review.Round, review.Provider, review.Comments)
		}
	}
	for _, failure := range parsed.Errors {
		fmt.Fprintf(os.Stderr, "! %s failed at the %s turn: %s\n", failure.Provider, failure.Stage, failure.Error)
	}

	s.history = append(s.history, exchange{Question: question, Answer: parsed.Answer})
	s.spent += parsed.CoinsCharged
	s.turns++

	outcome := "stopped: " + parsed.StoppedReason
	if parsed.Settled {
		outcome = "settled"
	}
	fmt.Fprintf(os.Stderr, "\n%s · %d round(s), %d calls · %d files listed",
		outcome, parsed.Rounds, parsed.Calls, gathered.Listed)
	if gathered.FileCount > 0 {
		fmt.Fprintf(os.Stderr, ", %d in full", gathered.FileCount)
	}
	fmt.Fprintln(os.Stderr)
	if balanceErr == nil {
		if balanceAfter, afterErr := client.balance(wallet.Address); afterErr == nil {
			fmt.Fprintln(os.Stderr, coinBar(balanceBefore, balanceAfter, parsed.CoinsCharged))
			return nil
		}
	}
	fmt.Fprintf(os.Stderr, "%d aicoin spent\n", parsed.CoinsCharged)
	return nil
}

const sessionHelp = `  /f <glob>        include these files' contents in every question (blank to clear)
  /panel <a,b>     which models sit on the panel (blank for all of them)
  /rounds <n>      cap the review rounds
  /v               show or hide each round's comments
  /files           what the panel can currently see
  /balance         what the wallet holds
  /reset           forget this session's exchanges
  /help            this
  /exit            leave (Ctrl-D does too)`

// command handles a /line. It returns true when the session should end.
func (s *sessionState) command(line string, client *Client, wallet *Wallet) (bool, error) {
	fields := strings.Fields(line)
	rest := strings.TrimSpace(strings.TrimPrefix(line, fields[0]))
	switch fields[0] {
	case "/exit", "/quit", "/q":
		return true, nil
	case "/help", "/?":
		fmt.Fprintln(os.Stderr, sessionHelp)
	case "/f", "/files":
		if fields[0] == "/f" {
			if rest == "" {
				s.includes = nil
				fmt.Fprintln(os.Stderr, "including no file contents now — just the listing")
				return false, nil
			}
			s.includes = nil
			if err := s.includes.Set(rest); err != nil {
				return false, err
			}
		}
		gathered, err := gatherDir(s.dir, s.includes, s.budget)
		if err != nil {
			return false, err
		}
		fmt.Fprintf(os.Stderr, "%d files listed", gathered.Listed)
		if gathered.FileCount > 0 {
			fmt.Fprintf(os.Stderr, ", %d included in full", gathered.FileCount)
		}
		fmt.Fprintf(os.Stderr, " · %d chars", gathered.Chars)
		if gathered.Truncated {
			fmt.Fprint(os.Stderr, " (trimmed to fit)")
		}
		fmt.Fprintln(os.Stderr)
	case "/panel":
		s.providers = rest
		if rest == "" {
			fmt.Fprintln(os.Stderr, "panel: every configured model")
		} else {
			fmt.Fprintf(os.Stderr, "panel: %s\n", rest)
		}
	case "/rounds":
		if rest == "" {
			return false, fmt.Errorf("usage: /rounds <n>")
		}
		n, err := strconv.Atoi(rest)
		if err != nil || n < 1 {
			return false, fmt.Errorf("rounds must be a positive number")
		}
		s.rounds = n
		fmt.Fprintf(os.Stderr, "at most %d review round(s)\n", n)
	case "/v":
		s.verbose = !s.verbose
		fmt.Fprintf(os.Stderr, "round comments: %v\n", s.verbose)
	case "/reset":
		s.history = nil
		fmt.Fprintln(os.Stderr, "this session's exchanges are forgotten")
	case "/balance":
		balance, err := client.balance(wallet.Address)
		if err != nil {
			return false, err
		}
		fmt.Fprintf(os.Stderr, "%s aicoin\n", formatCoins(balance))
	default:
		return false, fmt.Errorf("no such command %q — /help lists them", fields[0])
	}
	return false, nil
}
