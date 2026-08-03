// Package state computes derived state from the chain: per-user balances
// and the recency-weighted price statistics described in CONTRACT.md's
// "Derived state — price (final formula, v2: smooth exponential decay)"
// section. None of this is stored separately — it is recomputed from the
// chain on every query.
package state

import (
	"math"
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

// DefaultHalfLifeDays is the default value of the -decay-halflife-days CLI
// flag, per CONTRACT.md's "Derived state — price (final formula, v2: smooth
// exponential decay)" section: calibrated from a real, documented industry
// data point (AI inference/API pricing has fallen roughly 10x per year
// across major providers), giving a half-life of
// 365.25 * ln(2)/ln(10) ≈ 110 days.
const DefaultHalfLifeDays = 110.0

// Weight computes the smooth exponential recency weight for an event whose
// age (in days, i.e. now - event.Timestamp expressed in days) is ageDays,
// per CONTRACT.md's "Derived state — price (final formula, v2: smooth
// exponential decay)" section:
//
//	weight(age) = 2 ^ (-age_days / halfLifeDays)
//
// A negative ageDays (clock skew or a future-dated event timestamp) is
// clamped to 0, yielding weight = 1.0.
func Weight(ageDays, halfLifeDays float64) float64 {
	if ageDays < 0 {
		ageDays = 0
	}
	return math.Pow(2, -ageDays/halfLifeDays)
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
// CONTRACT.md's "Derived state — price (final formula, v2: smooth
// exponential decay)" section:
//
//	price_usd = Σ(weight_i * cost_usd_i) / Σ(weight_i)
//
// where each event's weight is Weight(ageDays, halfLifeDays), ageDays being
// now.Sub(event timestamp) expressed in days (negative ageDays from clock
// skew/future timestamps clamps to 0 inside Weight). now is the wall-clock
// time to weight against (callers pass time.Now().UTC() in production;
// tests pass a fixed instant). Transactions whose Timestamp cannot be
// parsed are ignored, same as event transactions of any other type.
//
//   - total_spend_usd is the plain unweighted all-time sum of cost_usd.
//   - weighted_total is Σweight_i, the formula's denominator.
//   - Zero events yields price_usd=0 rather than dividing by zero.
func Price(blocks []chain.Block, now time.Time, halfLifeDays float64) PriceStats {
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
			ageDays := now.Sub(ts).Hours() / 24
			w := Weight(ageDays, halfLifeDays)
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
