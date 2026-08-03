package p2p

import "encoding/json"

// Envelope is the newline-delimited JSON message envelope used on the P2P
// TCP wire, per CONTRACT.md:
//
//	{"type": "hello"|"block"|"chain_request"|"chain_response", "payload": ...}
//
// Payload is kept as raw JSON on decode so that the concrete type can be
// chosen based on Type before unmarshaling.
type Envelope struct {
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload"`
}

const (
	msgHello         = "hello"
	msgBlock         = "block"
	msgChainRequest  = "chain_request"
	msgChainResponse = "chain_response"
)

// newEnvelope marshals payload (which may be nil) into an Envelope of the
// given type, ready to be encoded onto the wire.
func newEnvelope(msgType string, payload interface{}) (Envelope, error) {
	if payload == nil {
		return Envelope{Type: msgType, Payload: json.RawMessage("null")}, nil
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return Envelope{}, err
	}
	return Envelope{Type: msgType, Payload: raw}, nil
}
