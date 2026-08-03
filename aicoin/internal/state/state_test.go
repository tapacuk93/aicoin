package state

import (
	"testing"
	"time"

	"aicoin/internal/chain"
)

func txAt(userID, provider string, cost float64, ts time.Time) chain.Transaction {
	return chain.Transaction{
		Type:      "event",
		UserID:    userID,
		Provider:  provider,
		CostUSD:   cost,
		Timestamp: ts.Format(time.RFC3339),
	}
}

func blockWith(index int, tx chain.Transaction) chain.Block {
	return chain.Block{Index: index, Transactions: []chain.Transaction{tx}}
}

func freeClaimAt(userID string, ts time.Time) chain.Transaction {
	return chain.Transaction{
		Type:      "free_claim",
		UserID:    userID,
		Timestamp: ts.Format(time.RFC3339),
	}
}

func transferAt(from, to string, amount float64, ts time.Time) chain.Transaction {
	return chain.Transaction{
		Type:       "transfer",
		FromUserID: from,
		ToUserID:   to,
		Amount:     amount,
		Timestamp:  ts.Format(time.RFC3339),
	}
}

// TestPriceWeightedAcrossAllSixBuckets builds one synthetic event per
// recency bucket from CONTRACT.md's "Derived state — price (final
// formula)" section, relative to a fixed "now" this test fully controls,
// and checks price_usd/total_spend_usd/weighted_total against a
// hand-computed weighted average using the exact same (default) weights.
//
// "now" is deliberately a Thursday (2026-08-20) so that dates a couple of
// days apart land in the same ISO week without crossing a Monday boundary,
// and August 3rd (a Monday) lands in a different ISO week but the same
// month, giving every bucket a distinct, unambiguous representative
// timestamp:
//
//   - hour:  now itself                                  -> decay.hour
//   - day:   same Y/M/D, different hour                  -> decay.day
//   - week:  2026-08-18 (same ISO year+week, diff day)    -> decay.week
//   - month: 2026-08-03 (same Y/M, diff ISO week)          -> decay.month
//   - year:  2026-01-15 (same year, diff month)            -> decay.year
//   - older: 2025-12-31 (prior year)                       -> decay.older
func TestPriceWeightedAcrossAllSixBuckets(t *testing.T) {
	now := time.Date(2026, 8, 20, 15, 0, 0, 0, time.UTC)

	hourTS := now
	dayTS := now.Add(-3 * time.Hour)                         // 2026-08-20T12:00:00Z: same day, different hour
	weekTS := time.Date(2026, 8, 18, 12, 0, 0, 0, time.UTC)  // same ISO week (Aug 17-23), different day
	monthTS := time.Date(2026, 8, 3, 9, 0, 0, 0, time.UTC)   // same month, different ISO week
	yearTS := time.Date(2026, 1, 15, 9, 0, 0, 0, time.UTC)   // same year, different month
	olderTS := time.Date(2025, 12, 31, 9, 0, 0, 0, time.UTC) // prior year

	// Sanity-check the calendar assumptions the bucket assignment above
	// depends on, so a future change to these constants fails loudly here
	// rather than silently miscomputing the "hand-computed" expectation.
	if wy, ww := weekTS.ISOWeek(); true {
		if ny, nw := now.ISOWeek(); wy != ny || ww != nw {
			t.Fatalf("test invariant broken: weekTS ISOWeek=(%d,%d) does not match now's ISOWeek=(%d,%d)", wy, ww, ny, nw)
		}
	}
	if monthTS.Year() != now.Year() || monthTS.Month() != now.Month() {
		t.Fatalf("test invariant broken: monthTS is not in the same year+month as now")
	}
	if my, mw := monthTS.ISOWeek(); true {
		if ny, nw := now.ISOWeek(); my == ny && mw == nw {
			t.Fatalf("test invariant broken: monthTS must NOT be in the same ISO week as now")
		}
	}

	const hourCost, dayCost, weekCost, monthCost, yearCost, olderCost = 1.0, 2.0, 3.0, 4.0, 5.0, 6.0

	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, txAt("alice", "openai", hourCost, hourTS)),
		blockWith(2, txAt("bob", "anthropic", dayCost, dayTS)),
		blockWith(3, txAt("carol", "google", weekCost, weekTS)),
		blockWith(4, txAt("dave", "mistral", monthCost, monthTS)),
		blockWith(5, txAt("eve", "cohere", yearCost, yearTS)),
		blockWith(6, txAt("frank", "openai", olderCost, olderTS)),
	}

	weights := DefaultDecayWeights()
	stats := Price(blocks, now, weights)

	wantTotal := hourCost + dayCost + weekCost + monthCost + yearCost + olderCost
	wantWeightedTotal := weights.Hour + weights.Day + weights.Week + weights.Month + weights.Year + weights.Older
	wantWeightedSum := weights.Hour*hourCost + weights.Day*dayCost + weights.Week*weekCost +
		weights.Month*monthCost + weights.Year*yearCost + weights.Older*olderCost
	wantPrice := wantWeightedSum / wantWeightedTotal

	if !floatEquals(stats.TotalSpendUSD, wantTotal) {
		t.Errorf("total_spend_usd = %v, want %v", stats.TotalSpendUSD, wantTotal)
	}
	if !floatEquals(stats.WeightedTotal, wantWeightedTotal) {
		t.Errorf("weighted_total = %v, want %v", stats.WeightedTotal, wantWeightedTotal)
	}
	if !floatEquals(stats.PriceUSD, wantPrice) {
		t.Errorf("price_usd = %v, want %v", stats.PriceUSD, wantPrice)
	}
}

// TestPriceCustomWeightsFlowThrough proves the weights parameter actually
// drives the computation (not just the defaults): with all events pinned
// to the "hour" bucket, price_usd must equal the plain unweighted average
// regardless of what the other five weights are set to (their bucket has
// zero events, so they must not influence the result at all).
func TestPriceCustomWeightsFlowThrough(t *testing.T) {
	now := time.Date(2026, 8, 20, 15, 0, 0, 0, time.UTC)
	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, txAt("alice", "openai", 0.10, now)),
		blockWith(2, txAt("bob", "anthropic", 0.30, now)),
	}

	weights := DecayWeights{Hour: 7.0, Day: 999, Week: 999, Month: 999, Year: 999, Older: 999}
	stats := Price(blocks, now, weights)

	// Both events share the same "hour" weight, so it cancels out of the
	// weighted average entirely: price_usd = plain mean of cost_usd.
	wantPrice := (0.10 + 0.30) / 2.0
	if !floatEquals(stats.PriceUSD, wantPrice) {
		t.Errorf("price_usd = %v, want %v (weight should cancel when all events share one bucket)", stats.PriceUSD, wantPrice)
	}
	wantWeightedTotal := 2 * weights.Hour
	if !floatEquals(stats.WeightedTotal, wantWeightedTotal) {
		t.Errorf("weighted_total = %v, want %v", stats.WeightedTotal, wantWeightedTotal)
	}
}

// TestPriceZeroEvents covers the zero-event case explicitly: price_usd,
// total_spend_usd and weighted_total are all zero.
func TestPriceZeroEvents(t *testing.T) {
	blocks := []chain.Block{chain.Genesis()}
	now := time.Date(2026, 8, 20, 15, 0, 0, 0, time.UTC)

	stats := Price(blocks, now, DefaultDecayWeights())

	if stats.TotalSpendUSD != 0 {
		t.Errorf("total_spend_usd = %v, want 0", stats.TotalSpendUSD)
	}
	if stats.WeightedTotal != 0 {
		t.Errorf("weighted_total = %v, want 0", stats.WeightedTotal)
	}
	if stats.PriceUSD != 0 {
		t.Errorf("price_usd = %v, want 0", stats.PriceUSD)
	}
}

// TestBalanceEventTxsDoNotMint proves the closed-set coin-acquisition rule
// from CONTRACT.md's "Derived state" section: "event" transactions (priced
// AI-provider calls) never mint aicoin by themselves, no matter how many a
// user has. Only "free_claim" and "transfer" transactions affect balance.
func TestBalanceEventTxsDoNotMint(t *testing.T) {
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, txAt("alice", "openai", 0.10, now.Add(-10*time.Minute))),
		blockWith(2, txAt("bob", "anthropic", 0.20, now.Add(-30*time.Minute))),
		blockWith(3, txAt("alice", "openai", 0.05, now.Add(-3*time.Hour))),
	}

	if got := Balance(blocks, "alice"); got != 0.0 {
		t.Errorf("Balance(alice) = %v, want 0.0 (event txs must not mint)", got)
	}
	if got := Balance(blocks, "bob"); got != 0.0 {
		t.Errorf("Balance(bob) = %v, want 0.0 (event txs must not mint)", got)
	}
}

// TestBalanceFreeClaimTxsMintOneEach proves each "free_claim" tx for a user
// contributes exactly +1.0, and that "event" txs interleaved in the same
// chain still contribute nothing.
func TestBalanceFreeClaimTxsMintOneEach(t *testing.T) {
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, freeClaimAt("alice", now.Add(-3*time.Hour))),
		blockWith(2, txAt("alice", "openai", 0.10, now.Add(-2*time.Hour))), // must not mint
		blockWith(3, freeClaimAt("alice", now.Add(-1*time.Hour))),
		blockWith(4, freeClaimAt("bob", now.Add(-30*time.Minute))),
	}

	if got := Balance(blocks, "alice"); got != 2.0 {
		t.Errorf("Balance(alice) = %v, want 2.0", got)
	}
	if got := Balance(blocks, "bob"); got != 1.0 {
		t.Errorf("Balance(bob) = %v, want 1.0", got)
	}
	if got := Balance(blocks, "nobody"); got != 0.0 {
		t.Errorf("Balance(nobody) = %v, want 0.0", got)
	}
}

// TestBalanceTransferMovesAmountBetweenUsers proves the peer-transfer
// derived-balance effect from CONTRACT.md's "Peer transfer (buy/sell)"
// section: balances[FromUserID] -= Amount; balances[ToUserID] += Amount.
func TestBalanceTransferMovesAmountBetweenUsers(t *testing.T) {
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, freeClaimAt("alice", now.Add(-3*time.Hour))),
		blockWith(2, freeClaimAt("alice", now.Add(-2*time.Hour))),
		blockWith(3, txAt("alice", "openai", 0.10, now.Add(-90*time.Minute))), // must not affect balance
		blockWith(4, transferAt("alice", "bob", 0.5, now.Add(-1*time.Hour))),
	}

	if got := Balance(blocks, "alice"); !floatEquals(got, 1.5) {
		t.Errorf("Balance(alice) = %v, want 1.5 (2.0 free_claim - 0.5 transferred out)", got)
	}
	if got := Balance(blocks, "bob"); !floatEquals(got, 0.5) {
		t.Errorf("Balance(bob) = %v, want 0.5 (received transfer)", got)
	}
}

// TestBalanceMultipleTransfersNetOut proves several transfers in and out
// of the same user all net together correctly, in chain order.
func TestBalanceMultipleTransfersNetOut(t *testing.T) {
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, freeClaimAt("alice", now.Add(-5*time.Hour))),
		blockWith(2, freeClaimAt("alice", now.Add(-4*time.Hour))),
		blockWith(3, freeClaimAt("alice", now.Add(-3*time.Hour))),
		blockWith(4, transferAt("alice", "bob", 1.0, now.Add(-2*time.Hour))),
		blockWith(5, transferAt("carol", "alice", 0.25, now.Add(-1*time.Hour))),
	}
	// carol has no prior mint, so her balance goes negative — this package
	// doesn't enforce non-negativity (that's the API layer's job at
	// transfer time); Balance is a pure derived sum.
	if got := Balance(blocks, "alice"); !floatEquals(got, 2.25) {
		t.Errorf("Balance(alice) = %v, want 2.25 (3.0 minted - 1.0 sent + 0.25 received)", got)
	}
	if got := Balance(blocks, "bob"); !floatEquals(got, 1.0) {
		t.Errorf("Balance(bob) = %v, want 1.0", got)
	}
	if got := Balance(blocks, "carol"); !floatEquals(got, -0.25) {
		t.Errorf("Balance(carol) = %v, want -0.25", got)
	}
}

// TestFaucetEligibilityNeverClaimedBefore covers the "no prior free_claim"
// case: eligible must be true and hasPrevious false.
func TestFaucetEligibilityNeverClaimedBefore(t *testing.T) {
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	blocks := []chain.Block{chain.Genesis()}

	eligible, _, hasPrevious := FaucetEligibility(blocks, "alice", now)
	if !eligible {
		t.Error("eligible = false, want true (no prior claim)")
	}
	if hasPrevious {
		t.Error("hasPrevious = true, want false (no prior claim)")
	}
}

// TestFaucetEligibilityGrantedAfterOneHour covers a prior claim exactly (or
// more than) 1 hour in the past: eligible again.
func TestFaucetEligibilityGrantedAfterOneHour(t *testing.T) {
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	lastClaim := now.Add(-1 * time.Hour) // exactly 1h ago: boundary is inclusive
	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, freeClaimAt("alice", lastClaim)),
	}

	eligible, got, hasPrevious := FaucetEligibility(blocks, "alice", now)
	if !eligible {
		t.Error("eligible = false, want true (last claim exactly 1h ago)")
	}
	if !hasPrevious {
		t.Error("hasPrevious = false, want true")
	}
	if !got.Equal(lastClaim) {
		t.Errorf("lastClaim = %v, want %v", got, lastClaim)
	}

	// Comfortably more than 1h ago is also eligible.
	blocks2 := []chain.Block{
		chain.Genesis(),
		blockWith(1, freeClaimAt("alice", now.Add(-2*time.Hour))),
	}
	if eligible, _, _ := FaucetEligibility(blocks2, "alice", now); !eligible {
		t.Error("eligible = false, want true (last claim 2h ago)")
	}
}

// TestFaucetEligibilityNotYetEligible covers a prior claim less than 1 hour
// in the past: not eligible, and next_eligible_at should be computable as
// lastClaim + 1h.
func TestFaucetEligibilityNotYetEligible(t *testing.T) {
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	lastClaim := now.Add(-30 * time.Minute)
	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, freeClaimAt("alice", lastClaim)),
	}

	eligible, got, hasPrevious := FaucetEligibility(blocks, "alice", now)
	if eligible {
		t.Error("eligible = true, want false (last claim only 30m ago)")
	}
	if !hasPrevious {
		t.Error("hasPrevious = false, want true")
	}
	if !got.Equal(lastClaim) {
		t.Errorf("lastClaim = %v, want %v", got, lastClaim)
	}
	wantNextEligible := lastClaim.Add(time.Hour)
	if nextEligible := got.Add(time.Hour); !nextEligible.Equal(wantNextEligible) {
		t.Errorf("next_eligible_at = %v, want %v", nextEligible, wantNextEligible)
	}
}

// TestFaucetEligibilityUsesMostRecentClaimForUser proves eligibility is
// based on the most recent free_claim tx for the given user, ignoring
// older claims for that user and claims belonging to other users.
func TestFaucetEligibilityUsesMostRecentClaimForUser(t *testing.T) {
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, freeClaimAt("alice", now.Add(-5*time.Hour))),    // old claim, would be eligible on its own
		blockWith(2, freeClaimAt("bob", now.Add(-1*time.Minute))),    // different user, must not affect alice
		blockWith(3, freeClaimAt("alice", now.Add(-10*time.Minute))), // alice's most recent
	}

	eligible, lastClaim, hasPrevious := FaucetEligibility(blocks, "alice", now)
	if !hasPrevious {
		t.Fatal("hasPrevious = false, want true")
	}
	wantLast := now.Add(-10 * time.Minute)
	if !lastClaim.Equal(wantLast) {
		t.Errorf("lastClaim = %v, want %v (most recent for alice)", lastClaim, wantLast)
	}
	if eligible {
		t.Error("eligible = true, want false (alice's most recent claim was 10m ago)")
	}
}

func floatEquals(a, b float64) bool {
	const eps = 1e-9
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < eps
}
