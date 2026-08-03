package state

import (
	"math"
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

// TestWeightMatchesSmoothExponentialFormula constructs synthetic events at
// controlled ages (relative to a fixed test "now") and checks each one's
// individual weight against a hand-computed 2^(-age_days/halfLifeDays),
// per CONTRACT.md's "Derived state — price (final formula, v2: smooth
// exponential decay)" section. It also cross-checks the default half-life
// (110 days) against CONTRACT.md's own named-checkpoint table (1h, 1d, 1w,
// 1mo, 1q, 1y, 5y), which was itself computed from this exact formula.
func TestWeightMatchesSmoothExponentialFormula(t *testing.T) {
	const halfLife = DefaultHalfLifeDays // 110.0

	cases := []struct {
		name    string
		ageDays float64
	}{
		{"0 days", 0},
		{"1 day", 1},
		{"7 days", 7},
		{"30.44 days (~1 month)", 30.44},
		{"91.31 days (~1 quarter)", 91.31},
		{"365.25 days (~1 year)", 365.25},
	}
	for _, c := range cases {
		got := Weight(c.ageDays, halfLife)
		want := math.Pow(2, -c.ageDays/halfLife)
		if diff := math.Abs(got - want); diff > 1e-9 {
			t.Errorf("%s: Weight(%v, %v) = %v, want %v (diff %v)", c.name, c.ageDays, halfLife, got, want, diff)
		}
	}

	// Cross-check against CONTRACT.md's named-checkpoint table under the
	// default half-life (generous tolerance since the table is rounded to
	// 3 significant figures).
	checkpoints := []struct {
		name    string
		ageDays float64
		want    float64
		tol     float64
	}{
		{"1 hour", 1.0 / 24, 1.000, 0.001},
		{"1 day", 1, 0.994, 0.001},
		{"1 week", 7, 0.957, 0.001},
		{"1 month (30.44d)", 30.44, 0.825, 0.001},
		{"1 quarter (91.31d)", 91.31, 0.563, 0.002},
		{"1 year (365.25d)", 365.25, 0.100, 0.0005},
		{"5 years", 5 * 365.25, 0.00001, 0.000005},
	}
	for _, c := range checkpoints {
		got := Weight(c.ageDays, halfLife)
		if diff := math.Abs(got - c.want); diff > c.tol {
			t.Errorf("checkpoint %s: Weight(%v, %v) = %v, want ~%v (tol %v)", c.name, c.ageDays, halfLife, got, c.want, c.tol)
		}
	}
}

// TestWeightFutureTimestampClampsToOne proves a negative age (clock skew,
// or an event timestamped in the future) clamps to age=0, giving weight
// exactly 1.0, per CONTRACT.md's clamp rule.
func TestWeightFutureTimestampClampsToOne(t *testing.T) {
	for _, ageDays := range []float64{-0.001, -1, -365} {
		if got := Weight(ageDays, DefaultHalfLifeDays); got != 1.0 {
			t.Errorf("Weight(%v, %v) = %v, want exactly 1.0 (negative age clamps to 0)", ageDays, DefaultHalfLifeDays, got)
		}
	}
}

// TestPriceAggregateMatchesHandComputedSmoothDecay builds a small
// multi-event scenario at exact multiples of the default half-life (so the
// per-event weights are round numbers: 1.0, 0.5, 0.25) and checks
// price_usd/total_spend_usd/weighted_total against a hand-computed
// Σ(weight_i*cost_usd_i)/Σ(weight_i).
func TestPriceAggregateMatchesHandComputedSmoothDecay(t *testing.T) {
	now := time.Date(2026, 8, 20, 15, 0, 0, 0, time.UTC)

	const halfLife = DefaultHalfLifeDays
	tsNow := now                                                         // age 0   -> weight 1.0
	tsOneHalfLife := now.Add(-time.Duration(halfLife*24) * time.Hour)    // age 110 -> weight 0.5
	tsTwoHalfLives := now.Add(-time.Duration(2*halfLife*24) * time.Hour) // age 220 -> weight 0.25

	const costA, costB, costC = 0.10, 0.20, 0.30

	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, txAt("alice", "openai", costA, tsNow)),
		blockWith(2, txAt("bob", "anthropic", costB, tsOneHalfLife)),
		blockWith(3, txAt("carol", "google", costC, tsTwoHalfLives)),
	}

	stats := Price(blocks, now, halfLife)

	const wA, wB, wC = 1.0, 0.5, 0.25
	wantWeightedTotal := wA + wB + wC
	wantWeightedSum := wA*costA + wB*costB + wC*costC
	wantPrice := wantWeightedSum / wantWeightedTotal
	wantTotalSpend := costA + costB + costC

	if !floatEquals(stats.TotalSpendUSD, wantTotalSpend) {
		t.Errorf("total_spend_usd = %v, want %v", stats.TotalSpendUSD, wantTotalSpend)
	}
	if diff := math.Abs(stats.WeightedTotal - wantWeightedTotal); diff > 1e-6 {
		t.Errorf("weighted_total = %v, want %v (diff %v)", stats.WeightedTotal, wantWeightedTotal, diff)
	}
	if diff := math.Abs(stats.PriceUSD - wantPrice); diff > 1e-6 {
		t.Errorf("price_usd = %v, want %v (diff %v)", stats.PriceUSD, wantPrice, diff)
	}
}

// TestPriceCustomHalfLifeFlowsThrough proves the halfLifeDays parameter
// actually drives the computation (not just the default 110): the same
// fixed-age event produces a different weight (and hence a different
// price_usd, when mixed with a same-instant event) under a short half-life
// than under the default one.
func TestPriceCustomHalfLifeFlowsThrough(t *testing.T) {
	now := time.Date(2026, 8, 20, 15, 0, 0, 0, time.UTC)
	agedTS := now.Add(-10 * 24 * time.Hour) // 10 days old

	blocks := []chain.Block{
		chain.Genesis(),
		blockWith(1, txAt("alice", "openai", 0.10, now)),     // age 0   -> weight 1.0 regardless of half-life
		blockWith(2, txAt("bob", "anthropic", 0.30, agedTS)), // age 10  -> weight depends on half-life
	}

	const customHalfLife = 10.0 // much shorter than the 110-day default
	statsCustom := Price(blocks, now, customHalfLife)
	statsDefault := Price(blocks, now, DefaultHalfLifeDays)

	if floatEquals(statsCustom.PriceUSD, statsDefault.PriceUSD) {
		t.Fatalf("price_usd identical (%v) under custom half-life %v and default half-life %v; the flag isn't flowing through",
			statsCustom.PriceUSD, customHalfLife, DefaultHalfLifeDays)
	}

	// Hand-compute the custom-half-life expectation directly.
	wNow := 1.0
	wAged := math.Pow(2, -10.0/customHalfLife)
	wantWeightedTotal := wNow + wAged
	wantWeightedSum := wNow*0.10 + wAged*0.30
	wantPrice := wantWeightedSum / wantWeightedTotal

	if diff := math.Abs(statsCustom.PriceUSD - wantPrice); diff > 1e-9 {
		t.Errorf("price_usd (custom half-life) = %v, want %v (diff %v)", statsCustom.PriceUSD, wantPrice, diff)
	}
}

// TestPriceZeroEvents covers the zero-event case explicitly: price_usd,
// total_spend_usd and weighted_total are all zero.
func TestPriceZeroEvents(t *testing.T) {
	blocks := []chain.Block{chain.Genesis()}
	now := time.Date(2026, 8, 20, 15, 0, 0, 0, time.UTC)

	stats := Price(blocks, now, DefaultHalfLifeDays)

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
