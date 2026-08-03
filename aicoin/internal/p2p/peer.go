package p2p

import (
	"encoding/json"
	"net"
	"sync"
)

// Peer is a single P2P TCP connection, in either direction. addr is the
// peer's advertised P2P listen address, filled in once its "hello" message
// arrives; until then it is empty.
type Peer struct {
	conn net.Conn
	enc  *json.Encoder
	dec  *json.Decoder

	encMu sync.Mutex

	mu   sync.Mutex
	addr string
}

func newPeer(conn net.Conn) *Peer {
	return &Peer{
		conn: conn,
		enc:  json.NewEncoder(conn),
		dec:  json.NewDecoder(conn),
	}
}

// Send encodes and writes one envelope to the peer. Safe for concurrent
// use: writes from multiple goroutines are serialized.
func (p *Peer) Send(msgType string, payload interface{}) error {
	env, err := newEnvelope(msgType, payload)
	if err != nil {
		return err
	}
	p.encMu.Lock()
	defer p.encMu.Unlock()
	return p.enc.Encode(env)
}

// Addr returns the peer's advertised P2P listen address, or "" if its
// hello message hasn't arrived yet.
func (p *Peer) Addr() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.addr
}

func (p *Peer) setAddr(addr string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.addr = addr
}

func (p *Peer) Close() error {
	return p.conn.Close()
}
