// Package state computes derived state from the chain: per-user balances
// and the recency-weighted price statistics described in CONTRACT.md's
// "Derived state — price (final formula)" section. None of this is stored
// separately — it is recomputed from the chain on every query.
package state

import (
	"time"

	"aicoin/internal/chain"
)

// Balance returns the derived balance of userID, per CONTRACT.md's
// "closed-set acquisition" rule: coins are minted only by "free_claim"
// (+1.0 each) and moved only by "transfer" (-Amount from FromUserID,
// +Amount to ToUserID). "event" transactions (priced AI-provider calls)
// never contribute to balance — they exist purely to feed the price
// formula below.
func Balance(blocks []chain.Block, userID string) float64 {
	var balance float64
	for _, b := range blocks {
		for _, tx := range b.Transactions {
			switch tx.Type {
			case "free_claim":
				if tx.UserID == userID {
					balance += 1.0
				}
			case "transfer":
				if tx.FromUserID == userID {
					balance -= tx.Amount
				}
				if tx.ToUserID == userID {
					balance += tx.Amount
				}
			}
		}
	}
	return balance
}

// FaucetEligibility reports whether userID may be granted a new free_claim
// at time now, based on the most recent free_claim transaction (by
// Timestamp) already recorded on chain for that user, per CONTRACT.md's
// "Free-coin faucet" section ("no more than 1 free coin per user per
// rolling hour").
//
//   - If the user has no prior free_claim on chain, eligible is true and
//     hasPrevious is false (lastClaim is the zero time.Time).
//   - Otherwise, hasPrevious is true, lastClaim is that most recent claim's
//     parsed Timestamp, and eligible is now.Sub(lastClaim) >= 1 hour.
//
// lastClaim is returned even when eligible is false so callers can compute
// next_eligible_at as lastClaim + 1h.
func FaucetEligibility(blocks []chain.Block, userID string, now time.Time) (eligible bool, lastClaim time.Time, hasPrevious bool) {
	for _, b := range blocks {
		for _, tx := range b.Transactions {
			if tx.Type != "free_claim" || tx.UserID != userID {
				continue
			}
			ts, err := time.Parse(time.RFC3339, tx.Timestamp)
			if err != nil {
				continue
			}
			if !hasPrevious || ts.After(lastClaim) {
				lastClaim = ts
				hasPrevious = true
			}
		}
	}
	if !hasPrevious {
		return true, time.Time{}, false
	}
	return !now.Before(lastClaim.Add(time.Hour)), lastClaim, true
}

// DecayWeights holds the six recency-bucket weights from CONTRACT.md's
// "Derived state — price (final formula)" section, one per bucket,
// evaluated top-to-bottom with first-match-wins semantics in weightFor.
// These map 1:1 to the -decay-hour/-decay-day/-decay-week/-decay-month/
// -decay-year/-decay-older CLI flags.
type DecayWeights struct {
	Hour  float64
	Day   float64
	Week  float64
	Month float64
	Year  float64
	Older float64
}

// DefaultDecayWeights returns the documented default weights (a
// monotonically halving curve): Hour=1.0, Day=0.5, Week=0.25, Month=0.125,
// Year=0.0625, Older=0.03125.
func DefaultDecayWeights() DecayWeights {
	return DecayWeights{
		Hour:  1.0,
		Day:   0.5,
		Week:  0.25,
		Month: 0.125,
		Year:  0.0625,
		Older: 0.03125,
	}
}

// weightFor picks ts's weight relative to now, per CONTRACT.md: buckets are
// evaluated top-to-bottom, first match wins, comparing UTC calendar fields.
// Both ts and now are converted to UTC internally so callers may pass
// either UTC or non-UTC (but well-formed) time.Time values.
func weightFor(ts, now time.Time, w DecayWeights) float64 {
	ts = ts.UTC()
	now = now.UTC()

	tYear, tMonth, tDay := ts.Date()
	nYear, nMonth, nDay := now.Date()
	tHour := ts.Hour()
	nHour := now.Hour()
	tIsoYear, tIsoWeek := ts.ISOWeek()
	nIsoYear, nIsoWeek := now.ISOWeek()

	switch {
	case tYear == nYear && tMonth == nMonth && tDay == nDay && tHour == nHour:
		return w.Hour
	case tYear == nYear && tMonth == nMonth && tDay == nDay:
		return w.Day
	case tIsoYear == nIsoYear && tIsoWeek == nIsoWeek:
		return w.Week
	case tYear == nYear && tMonth == nMonth:
		return w.Month
	case tYear == nYear:
		return w.Year
	default:
		return w.Older
	}
}

// PriceStats holds the price statistics described in CONTRACT.md's
// "Derived state — price (final formula)" section.
type PriceStats struct {
	PriceUSD      float64
	TotalSpendUSD float64
	WeightedTotal float64
}

// Price computes 1 aicoin's price as a recency-weighted average of CostUSD
// across every "event" transaction ever recorded on chain, per
// CONTRACT.md's "Derived state — price (final formula)" section:
//
//	price_usd = Σ(weight_i * cost_usd_i) / Σ(weight_i)
//
// where each event's weight is chosen by weightFor(event timestamp, now,
// weights). now is the wall-clock time to weight against (callers pass
// time.Now().UTC() in production; tests pass a fixed instant). Transactions
// whose Timestamp cannot be parsed are ignored, same as event transactions
// of any other type.
//
//   - total_spend_usd is the plain unweighted all-time sum of cost_usd.
//   - weighted_total is Σweight_i, the formula's denominator.
//   - Zero events (or a weighted_total of exactly 0, e.g. every observed
//     event happens to land in a zero-weighted bucket) yields price_usd=0
//     rather than dividing by zero.
func Price(blocks []chain.Block, now time.Time, weights DecayWeights) PriceStats {
	var totalSpendUSD, weightedSum, weightedTotal float64

	for _, b := range blocks {
		for _, tx := range b.Transactions {
			if tx.Type != "event" {
				continue
			}
			ts, err := time.Parse(time.RFC3339, tx.Timestamp)
			if err != nil {
				continue
			}
			w := weightFor(ts, now, weights)
			totalSpendUSD += tx.CostUSD
			weightedSum += w * tx.CostUSD
			weightedTotal += w
		}
	}

	var priceUSD float64
	if weightedTotal != 0 {
		priceUSD = weightedSum / weightedTotal
	}

	return PriceStats{
		PriceUSD:      priceUSD,
		TotalSpendUSD: totalSpendUSD,
		WeightedTotal: weightedTotal,
	}
}
