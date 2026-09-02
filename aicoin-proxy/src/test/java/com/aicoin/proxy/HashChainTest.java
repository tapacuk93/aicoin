package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The two properties a chain rests on, both of them just what a hash is: you cannot walk backwards,
 * and you cannot find a second seed landing on the same tip.
 */
class HashChainTest {

    @Test
    void aLinkProvesItsPlaceInTheChain() {
        String seed = "a".repeat(64);
        String tip = HashChain.tip(seed, 10);

        // Paying three coins means revealing the link three steps back from the tip.
        String third = HashChain.tip(seed, 7);
        assertTrue(HashChain.walksTo(third, 3, tip), "hashing forward three times should reach the tip");
        assertFalse(HashChain.walksTo(third, 2, tip), "and only at the right distance");
        assertFalse(HashChain.walksTo(third, 4, tip));
    }

    @Test
    void aLinkSaysNothingAboutTheOnesBehindIt() {
        // A receiver paid three coins holds link 7. Nothing they can do with it reaches link 6,
        // which is the next payment and not theirs.
        String seed = "b".repeat(64);
        String seventh = HashChain.tip(seed, 7);
        String sixth = HashChain.tip(seed, 6);

        assertNotEquals(sixth, seventh);
        assertEquals(seventh, HashChain.hash(sixth), "the chain runs one way");
        // Which is to say: from `seventh`, `sixth` is a preimage — and there is no walking to it.
        assertFalse(HashChain.walksTo(seventh, 1, sixth));
    }

    @Test
    void aDifferentSeedIsADifferentChain() {
        assertNotEquals(HashChain.tip("c".repeat(64), 50), HashChain.tip("d".repeat(64), 50));
        // Same seed, different length: also a different chain, so a wallet cannot re-use a seed to
        // open two chains that share links.
        assertNotEquals(HashChain.tip("c".repeat(64), 50), HashChain.tip("c".repeat(64), 49));
    }

    @Test
    void nothingUnboundedIsWalkedOnSomebodyElsesRequest() {
        // `steps` arrives from an HTTP body; an unbounded walk would be a way to ask this process
        // to hash for as long as anybody likes.
        assertFalse(HashChain.walksTo("e".repeat(64), HashChain.MAX_LINKS + 1, "whatever"));
        assertFalse(HashChain.walksTo("e".repeat(64), 0, "whatever"));
        assertFalse(HashChain.walksTo("e".repeat(64), -1, "whatever"));
        assertFalse(HashChain.walksTo(null, 1, "whatever"));
    }

    @Test
    void oneSeedStandsInForAWholePurse() {
        // The point of the whole construction: 32 bytes at home instead of one signed note per coin.
        String seed = "f".repeat(64);
        String tip = HashChain.tip(seed, 1000);
        for (int spent : new int[] {1, 10, 500, 1000}) {
            assertTrue(HashChain.walksTo(HashChain.tip(seed, 1000 - spent), spent, tip),
                    "any prefix of the chain should verify against the same tip");
        }
    }
}
