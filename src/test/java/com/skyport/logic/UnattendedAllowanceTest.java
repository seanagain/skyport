package com.skyport.logic;

import com.skyport.SkyportConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's allowance for aircraft that keep running with nobody near
 * them.
 *
 * The countdown itself lives in the block entity and needs a server, so what
 * is pinned here is the declared setting and the arithmetic that turns it
 * into ticks - which is where a wrong value would come from now that this is
 * config rather than a field on each schedule.
 */
class UnattendedAllowanceTest {

    /** Not zero. An aircraft that sleeps at every gate never completes a
     *  loop unless a player walks it round, which is the behaviour this
     *  setting exists to escape - so the shipped default is the interesting
     *  one, with zero available for anyone who wants the old way. */
    @Test
    void defaultsToFifteenMinutes() {
        assertEquals(15, SkyportConfig.UNATTENDED_MINUTES.getDefault());
    }

    /** The cached field has to agree with the spec, because it is what every
     *  caller actually reads and it is used before any config file has been
     *  loaded - on a client that has not joined a server yet, for instance. */
    @Test
    void theCachedFieldMatchesTheDeclaredDefault() {
        assertEquals(SkyportConfig.UNATTENDED_MINUTES.getDefault(),
                Integer.valueOf(SkyportConfig.unattendedMinutes));
    }

    /** Fifteen minutes is also the ceiling. This pins chunks open for its
     *  whole length, so an operator who wants more should be reaching for
     *  performance.keepParkedLoaded and accepting that cost knowingly,
     *  rather than typing a larger number into a timer. */
    @Test
    void isCappedAtTheDefault() {
        assertTrue(SkyportConfig.UNATTENDED_MINUTES.getSpec().test(15));
        assertTrue(SkyportConfig.UNATTENDED_MINUTES.getSpec().test(0));
        assertTrue(!SkyportConfig.UNATTENDED_MINUTES.getSpec().test(16),
                "16 minutes should be rejected, not silently accepted");
        assertTrue(!SkyportConfig.UNATTENDED_MINUTES.getSpec().test(-1),
                "negative time is not a thing");
    }

    /** Minutes to ticks, the conversion both the engage reset and the
     *  per-tick spend use. Wrong by a factor of sixty is the classic way to
     *  get this wrong, and it would look like the allowance lapsing almost
     *  immediately. */
    @Test
    void fifteenMinutesIsEighteenThousandTicks() {
        assertEquals(18_000, SkyportConfig.unattendedMinutes * 60 * 20);
    }
}
