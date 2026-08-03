package p2p

import (
	"crypto/ed25519"
	"fmt"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"aicoin/internal/chain"
)

// This test sandbox disallows binding to an ephemeral port (":0"); it only
// permits binding explicit fixed ports. So each test node gets its own
// never-reused fixed port from this counter.
var nextTestPort atomic.Int32

func init() {
	nextTestPort.Store(19800)
}

func testPortAddr() string {
	port := nextTestPort.Add(1)
	return fmt.Sprintf("127.0.0.1:%d", port)
}

// waitFor polls cond every 10ms up to timeout, failing the test if it never
// becomes true. Useful for waiting on async gossip/sync over real TCP
// connections.
func waitFor(t *testing.T, timeout time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !cond() {
		t.Fatalf("condition not met within %s", timeout)
	}
}

func genKeyPair(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatalf("ed25519.GenerateKey: %v", err)
	}
	return pub, priv
}

func startNode(t *testing.T, role string, bc *chain.Blockchain) *Node {
	t.Helper()
	n := NewNode(testPortAddr(), role, bc)
	if err := n.Listen(); err != nil {
		// Some sandboxed CI environments deny raw TCP bind/listen
		// syscalls outright (observed here as "operation not
		// permitted" even for loopback addresses), independent of any
		// bug in this package. Skip rather than fail in that case —
		// this test exercises real TCP networking and passes on any
		// host where socket binding is actually permitted.
		if strings.Contains(err.Error(), "operation not permitted") {
			t.Skipf("skipping: TCP bind not permitted in this sandbox: %v", err)
		}
		t.Fatalf("Listen: %v", err)
	}
	go n.Serve()
	t.Cleanup(func() { n.ln.Close() })
	return n
}

// TestNodeGossipsSealedBlockOverRealTCP starts a primary and a follower
// Node on real TCP sockets on loopback, connects the follower to the
// primary, waits for the startup handshake to converge (both nodes agree
// on genesis), then seals+signs a block on the primary and verifies it
// propagates to the follower over the wire and is accepted.
func TestNodeGossipsSealedBlockOverRealTCP(t *testing.T) {
	pub, priv := genKeyPair(t)

	primaryChain := chain.NewBlockchain(pub, priv)
	followerChain := chain.NewBlockchain(pub, nil)

	nodeA := startNode(t, "primary", primaryChain)
	nodeB := startNode(t, "follower", followerChain)

	nodeB.ConnectToPeers([]string{nodeA.ListenAddr})

	// Startup handshake: both sides should see one connected peer.
	waitFor(t, 2*time.Second, func() bool { return len(nodeA.Peers()) == 1 })
	waitFor(t, 2*time.Second, func() bool { return len(nodeB.Peers()) == 1 })

	block, err := primaryChain.SealAndAppend(chain.Transaction{
		Type:      "event",
		UserID:    "alice",
		Provider:  "openai",
		CostUSD:   0.01,
		Timestamp: "2026-08-03T12:00:00Z",
	})
	if err != nil {
		t.Fatalf("SealAndAppend: %v", err)
	}
	nodeA.BroadcastBlock(block, nil)

	waitFor(t, 2*time.Second, func() bool {
		return followerChain.Tip().Hash == block.Hash && followerChain.Tip().Index == block.Index
	})
}

// TestNodeSyncsLongerChainOnConnect starts a primary with a 3-block head
// start before a follower ever connects, then connects the follower to
// the primary and checks that the startup hello/chain_request/
// chain_response handshake brings the follower's chain up to match the
// primary's via the longest-valid-chain rule (no block gossip involved —
// this exercises pure startup sync).
func TestNodeSyncsLongerChainOnConnect(t *testing.T) {
	pub, priv := genKeyPair(t)

	primaryChain := chain.NewBlockchain(pub, priv)
	for i := 0; i < 3; i++ {
		if _, err := primaryChain.SealAndAppend(chain.Transaction{
			Type:      "event",
			UserID:    "alice",
			Provider:  "openai",
			CostUSD:   0.01,
			Timestamp: "2026-08-03T12:00:00Z",
		}); err != nil {
			t.Fatalf("SealAndAppend: %v", err)
		}
	}
	nodeA := startNode(t, "primary", primaryChain)

	followerChain := chain.NewBlockchain(pub, nil)
	nodeB := startNode(t, "follower", followerChain)
	nodeB.ConnectToPeers([]string{nodeA.ListenAddr})

	waitFor(t, 2*time.Second, func() bool {
		return followerChain.Len() == primaryChain.Len() && followerChain.Tip().Hash == primaryChain.Tip().Hash
	})
}

// newInProcessPeer builds a *Peer backed by one end of a net.Pipe — an
// in-memory, synchronous net.Conn that never touches the OS network stack
// (no bind/listen/socket syscalls at all), so tests using it run even in
// sandboxes that deny raw TCP. It is only usable for dispatch paths that
// don't call peer.Send with anything the test doesn't also drain, which
// holds for every case exercised below.
func newInProcessPeer(t *testing.T) *Peer {
	t.Helper()
	clientConn, serverConn := net.Pipe()
	t.Cleanup(func() {
		clientConn.Close()
		serverConn.Close()
	})
	return newPeer(serverConn)
}

// TestFollowerAdoptsLongerValidChainViaDispatch simulates a follower
// receiving a chain_response from a peer, without any real TCP socket —
// see newInProcessPeer. It proves the follower half of CONTRACT.md's
// "Chain-replacement asymmetry" rule: adopt a longer chain if every block
// in it validates against the configured trusted pubkey.
func TestFollowerAdoptsLongerValidChainViaDispatch(t *testing.T) {
	pub, priv := genKeyPair(t)

	primaryChain := chain.NewBlockchain(pub, priv)
	for i := 0; i < 3; i++ {
		if _, err := primaryChain.SealAndAppend(chain.Transaction{
			Type:      "event",
			UserID:    "alice",
			Provider:  "openai",
			CostUSD:   0.01,
			Timestamp: "2026-08-03T12:00:00Z",
		}); err != nil {
			t.Fatalf("SealAndAppend: %v", err)
		}
	}

	followerChain := chain.NewBlockchain(pub, nil)
	node := NewNode("unused", "follower", followerChain)

	env, err := newEnvelope(msgChainResponse, primaryChain.Blocks())
	if err != nil {
		t.Fatalf("newEnvelope: %v", err)
	}
	node.dispatch(newInProcessPeer(t), env)

	if followerChain.Len() != primaryChain.Len() {
		t.Fatalf("follower chain len = %d, want %d after adopting longer valid chain", followerChain.Len(), primaryChain.Len())
	}
	if followerChain.Tip().Hash != primaryChain.Tip().Hash {
		t.Fatalf("follower tip hash = %q, want %q", followerChain.Tip().Hash, primaryChain.Tip().Hash)
	}
}

// TestPrimaryNeverReplacesChainViaDispatch proves the other half of the
// asymmetry: a primary node must never replace its own chain based on an
// incoming chain_response, no matter how long (or how validly signed by
// its own trusted key) the offered chain is — it is authoritative by
// construction. Exercised via the same in-process dispatch path (no real
// sockets).
func TestPrimaryNeverReplacesChainViaDispatch(t *testing.T) {
	pub, priv := genKeyPair(t)

	primaryChain := chain.NewBlockchain(pub, priv)
	node := NewNode("unused", "primary", primaryChain)

	// A longer, fully valid chain signed by the very same trusted key —
	// even so, a primary must not adopt it.
	otherChain := chain.NewBlockchain(pub, priv)
	for i := 0; i < 5; i++ {
		if _, err := otherChain.SealAndAppend(chain.Transaction{
			Type:      "event",
			UserID:    "bob",
			Provider:  "anthropic",
			CostUSD:   0.02,
			Timestamp: "2026-08-03T12:00:00Z",
		}); err != nil {
			t.Fatalf("SealAndAppend: %v", err)
		}
	}

	env, err := newEnvelope(msgChainResponse, otherChain.Blocks())
	if err != nil {
		t.Fatalf("newEnvelope: %v", err)
	}
	node.dispatch(newInProcessPeer(t), env)

	if primaryChain.Len() != 1 {
		t.Fatalf("primary chain len = %d, want unchanged 1 (genesis only); a primary must never replace its own chain via gossip", primaryChain.Len())
	}
}

// TestPrimaryIgnoresIncomingBlockGossip proves a primary also ignores a
// "block" envelope from a peer outright (it only ever appends blocks it
// itself seals), exercised via the same in-process dispatch path.
func TestPrimaryIgnoresIncomingBlockGossip(t *testing.T) {
	pub, priv := genKeyPair(t)

	primaryChain := chain.NewBlockchain(pub, priv)
	node := NewNode("unused", "primary", primaryChain)

	otherChain := chain.NewBlockchain(pub, priv)
	block, err := otherChain.SealAndAppend(chain.Transaction{
		Type:      "event",
		UserID:    "bob",
		Provider:  "anthropic",
		CostUSD:   0.02,
		Timestamp: "2026-08-03T12:00:00Z",
	})
	if err != nil {
		t.Fatalf("SealAndAppend: %v", err)
	}

	env, err := newEnvelope(msgBlock, block)
	if err != nil {
		t.Fatalf("newEnvelope: %v", err)
	}
	node.dispatch(newInProcessPeer(t), env)

	if primaryChain.Len() != 1 {
		t.Fatalf("primary chain len = %d, want unchanged 1 (genesis only); a primary must ignore incoming block gossip", primaryChain.Len())
	}
}
