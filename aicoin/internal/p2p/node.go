// Package p2p implements the aicoin gossip protocol: plain TCP,
// newline-delimited JSON envelopes, per CONTRACT.md.
//
// On establishing a connection (outbound to a configured peer, or inbound
// accept), a node sends "hello" (payload = its own P2P listen address)
// followed by "chain_request" (payload = null), so that the remote side
// replies with its full chain ("chain_response") and the two nodes
// converge to the same (longest-valid) chain on startup.
//
// When a node seals (primary) or receives (follower) a new block, it
// gossips a "block" envelope to its other connected peers. Per
// CONTRACT.md's "Roles & signing" and "Chain-replacement asymmetry"
// sections, chain replacement is asymmetric by Role: a follower that
// receives a block that doesn't link to its local tip asks the sender for
// its full chain via "chain_request" and applies the longest-valid-chain
// rule upon the resulting "chain_response"; a primary never does either —
// it is authoritative by construction and only ever appends blocks it
// itself seals and signs.
package p2p

import (
	"encoding/json"
	"log"
	"net"
	"sort"
	"sync"
	"time"

	"aicoin/internal/chain"
)

// Node is a P2P gossip node bound to a single chain.Blockchain. Role is
// "primary" or "follower" (per CONTRACT.md's "Roles & signing" section)
// and gates whether this node ever accepts a block or a longer chain from
// a peer — see handleBlock and dispatch's msgChainResponse case.
type Node struct {
	ListenAddr string
	Role       string
	Chain      *chain.Blockchain

	ln net.Listener

	mu    sync.Mutex
	peers map[*Peer]struct{}
}

// NewNode creates a new P2P node. Listen must be called (and Serve run)
// before it will accept inbound connections.
func NewNode(listenAddr, role string, bc *chain.Blockchain) *Node {
	return &Node{
		ListenAddr: listenAddr,
		Role:       role,
		Chain:      bc,
		peers:      make(map[*Peer]struct{}),
	}
}

// Listen binds the P2P TCP listen socket. Errors (e.g. address already in
// use) are returned synchronously so callers can fail fast at startup.
func (n *Node) Listen() error {
	ln, err := net.Listen("tcp", n.ListenAddr)
	if err != nil {
		return err
	}
	n.ln = ln
	return nil
}

// Serve accepts inbound connections until the listener is closed or errors.
// It blocks; run it in its own goroutine.
func (n *Node) Serve() error {
	for {
		conn, err := n.ln.Accept()
		if err != nil {
			return err
		}
		go n.handleConn(conn)
	}
}

// ConnectToPeers dials each of the given P2P addresses in the background,
// retrying briefly if a peer isn't up yet.
func (n *Node) ConnectToPeers(addrs []string) {
	for _, addr := range addrs {
		go n.dialWithRetry(addr)
	}
}

func (n *Node) dialWithRetry(addr string) {
	const maxAttempts = 20
	const retryDelay = 500 * time.Millisecond

	for attempt := 1; attempt <= maxAttempts; attempt++ {
		conn, err := net.Dial("tcp", addr)
		if err == nil {
			n.handleConn(conn)
			return
		}
		time.Sleep(retryDelay)
	}
	log.Printf("p2p: giving up connecting to peer %s after %d attempts", addr, maxAttempts)
}

// handleConn runs the full lifecycle of one connection (either direction):
// handshake, then a read loop dispatching incoming envelopes, until the
// connection closes.
func (n *Node) handleConn(conn net.Conn) {
	peer := newPeer(conn)
	n.addPeer(peer)
	defer n.removePeer(peer)
	defer peer.Close()

	if err := peer.Send(msgHello, n.ListenAddr); err != nil {
		return
	}
	if err := peer.Send(msgChainRequest, nil); err != nil {
		return
	}

	for {
		var env Envelope
		if err := peer.dec.Decode(&env); err != nil {
			return
		}
		n.dispatch(peer, env)
	}
}

func (n *Node) dispatch(peer *Peer, env Envelope) {
	switch env.Type {
	case msgHello:
		var addr string
		if err := json.Unmarshal(env.Payload, &addr); err != nil {
			log.Printf("p2p: bad hello payload: %v", err)
			return
		}
		peer.setAddr(addr)

	case msgChainRequest:
		if err := peer.Send(msgChainResponse, n.Chain.Blocks()); err != nil {
			log.Printf("p2p: send chain_response: %v", err)
		}

	case msgChainResponse:
		if n.Role != "follower" {
			// Per CONTRACT.md: a primary is authoritative by construction
			// and never replaces its own chain based on incoming gossip/
			// sync, under any circumstances.
			return
		}
		var blocks []chain.Block
		if err := json.Unmarshal(env.Payload, &blocks); err != nil {
			log.Printf("p2p: bad chain_response payload: %v", err)
			return
		}
		if replaced, err := n.Chain.ReplaceIfLonger(blocks); err != nil {
			log.Printf("p2p: rejected chain_response from %s: %v", peer.Addr(), err)
		} else if replaced {
			log.Printf("p2p: replaced local chain with longer valid chain from %s (height %d)", peer.Addr(), n.Chain.Tip().Index)
		}

	case msgBlock:
		var b chain.Block
		if err := json.Unmarshal(env.Payload, &b); err != nil {
			log.Printf("p2p: bad block payload: %v", err)
			return
		}
		n.handleBlock(peer, b)

	default:
		log.Printf("p2p: unknown message type %q", env.Type)
	}
}

// handleBlock implements CONTRACT.md's block-receipt rule for a follower:
// if it links to the local tip and validates, append + re-gossip to other
// peers. Otherwise, if it's stale (we're already past it), ignore it.
// Otherwise (it's ahead of us but doesn't link, i.e. we may be behind on a
// longer chain), ask the sender for its full chain so the
// longest-valid-chain rule can be applied once chain_response arrives.
//
// A primary ignores any incoming "block" gossip entirely: it is
// authoritative by construction and only ever appends blocks it itself
// seals and signs.
func (n *Node) handleBlock(source *Peer, b chain.Block) {
	if n.Role != "follower" {
		return
	}

	tip := n.Chain.Tip()

	if b.Index == tip.Index+1 && b.PrevHash == tip.Hash {
		if err := n.Chain.Append(b); err != nil {
			log.Printf("p2p: rejected block %d from %s: %v", b.Index, source.Addr(), err)
			return
		}
		n.BroadcastBlock(b, source)
		return
	}

	if b.Index <= tip.Index {
		// Stale or duplicate; nothing to do.
		return
	}

	// Doesn't link (e.g. a fork at our tip height, or we're missing
	// blocks): fetch the sender's full chain and let the
	// longest-valid-chain rule (chain_response handler) decide.
	if err := source.Send(msgChainRequest, nil); err != nil {
		log.Printf("p2p: send chain_request: %v", err)
	}
}

// BroadcastBlock gossips b to every connected peer except exclude (which
// may be nil to broadcast to all peers, e.g. for a locally-mined block).
func (n *Node) BroadcastBlock(b chain.Block, exclude *Peer) {
	n.mu.Lock()
	targets := make([]*Peer, 0, len(n.peers))
	for p := range n.peers {
		if p != exclude {
			targets = append(targets, p)
		}
	}
	n.mu.Unlock()

	for _, p := range targets {
		if err := p.Send(msgBlock, b); err != nil {
			log.Printf("p2p: broadcast block %d to %s: %v", b.Index, p.Addr(), err)
		}
	}
}

// Peers returns the sorted, de-duplicated list of connected peers'
// advertised P2P listen addresses (peers whose hello hasn't arrived yet
// are omitted).
func (n *Node) Peers() []string {
	n.mu.Lock()
	defer n.mu.Unlock()

	seen := make(map[string]struct{})
	out := []string{}
	for p := range n.peers {
		addr := p.Addr()
		if addr == "" {
			continue
		}
		if _, ok := seen[addr]; ok {
			continue
		}
		seen[addr] = struct{}{}
		out = append(out, addr)
	}
	sort.Strings(out)
	return out
}

func (n *Node) addPeer(p *Peer) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.peers[p] = struct{}{}
}

func (n *Node) removePeer(p *Peer) {
	n.mu.Lock()
	defer n.mu.Unlock()
	delete(n.peers, p)
}
