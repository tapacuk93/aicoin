// Command aicoind is the aicoin P2P blockchain node, per CONTRACT.md: it
// starts both the HTTP API server and the P2P TCP gossip server against a
// single chain (in-memory only, or Redis-backed when -redis is set).
package main

import (
	"flag"
	"log"
	"net/http"
	"strings"

	"aicoin/internal/api"
	"aicoin/internal/chain"
	"aicoin/internal/p2p"
	"aicoin/internal/state"
	"aicoin/internal/store"
)

func main() {
	httpAddr := flag.String("http", ":9944", "HTTP API listen address")
	p2pAddr := flag.String("p2p", ":9945", "P2P TCP listen address")
	peersFlag := flag.String("peers", "", "comma-separated bootstrap peer P2P addresses (host:port,...)")
	difficulty := flag.Int("difficulty", 1, "number of required leading hex '0' chars in block hash")
	redisAddr := flag.String("redis", "", "optional Redis host:port for chain persistence (unset = in-memory only)")

	defaults := state.DefaultDecayWeights()
	decayHour := flag.Float64("decay-hour", defaults.Hour, "price weight for events in the same UTC hour as now")
	decayDay := flag.Float64("decay-day", defaults.Day, "price weight for events in the same UTC day as now")
	decayWeek := flag.Float64("decay-week", defaults.Week, "price weight for events in the same ISO week as now")
	decayMonth := flag.Float64("decay-month", defaults.Month, "price weight for events in the same UTC month as now")
	decayYear := flag.Float64("decay-year", defaults.Year, "price weight for events in the same UTC year as now")
	decayOlder := flag.Float64("decay-older", defaults.Older, "price weight for events from a prior UTC year")
	flag.Parse()

	peerAddrs := parsePeers(*peersFlag)
	weights := state.DecayWeights{
		Hour:  *decayHour,
		Day:   *decayDay,
		Week:  *decayWeek,
		Month: *decayMonth,
		Year:  *decayYear,
		Older: *decayOlder,
	}

	var chainStore chain.ChainStore
	if strings.TrimSpace(*redisAddr) != "" {
		chainStore = store.NewRedis(*redisAddr)
	}

	bc, err := chain.NewBlockchainWithStore(*difficulty, chainStore)
	if err != nil {
		log.Fatalf("aicoind: loading chain from redis %s: %v", *redisAddr, err)
	}

	node := p2p.NewNode(*p2pAddr, *difficulty, bc)

	if err := node.Listen(); err != nil {
		log.Fatalf("aicoind: p2p listen on %s: %v", *p2pAddr, err)
	}
	go func() {
		if err := node.Serve(); err != nil {
			log.Printf("aicoind: p2p serve stopped: %v", err)
		}
	}()

	if len(peerAddrs) > 0 {
		node.ConnectToPeers(peerAddrs)
	}

	srv := api.NewServer(bc, node, weights)

	log.Printf("aicoind: http=%s p2p=%s difficulty=%d peers=%v redis=%q genesis=%s",
		*httpAddr, *p2pAddr, *difficulty, peerAddrs, *redisAddr, chain.Genesis().Hash)

	if err := http.ListenAndServe(*httpAddr, srv.Router()); err != nil {
		log.Fatalf("aicoind: http listen on %s: %v", *httpAddr, err)
	}
}

func parsePeers(flagVal string) []string {
	if strings.TrimSpace(flagVal) == "" {
		return nil
	}
	var out []string
	for _, a := range strings.Split(flagVal, ",") {
		a = strings.TrimSpace(a)
		if a != "" {
			out = append(out, a)
		}
	}
	return out
}
