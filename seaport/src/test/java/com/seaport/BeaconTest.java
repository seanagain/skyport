package com.seaport;

import com.seaport.data.Beacon;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mark's name arrives in a packet, and a packet can say anything.
 *
 * Everything here is about what the server does with a name it was handed
 * rather than one it chose: this string is shown in other players' schedule
 * lists, so a formatting code in it restyles their screen, not just the
 * sender's.
 */
class BeaconTest {

    @Test
    void aBlankNameKeepsTheOldOne() {
        assertEquals("Channel", Beacon.cleanName("", "Channel"));
        assertEquals("Channel", Beacon.cleanName("   ", "Channel"));
        assertEquals("Channel", Beacon.cleanName(null, "Channel"));
    }

    @Test
    void formattingCodesAreStripped() {
        String cleaned = Beacon.cleanName("\u00a7cRed \u00a7lBold", "fallback");
        assertFalse(cleaned.contains("\u00a7"), "a name must not restyle the line it sits in");
        assertEquals("cRed lBold", cleaned);
    }

    @Test
    void controlCharactersAreStripped() {
        String cleaned = Beacon.cleanName("North\nChannel\u0007", "fallback");
        assertEquals("NorthChannel", cleaned);
    }

    @Test
    void anAbsurdlyLongNameIsCutToFit() {
        String cleaned = Beacon.cleanName("x".repeat(500), "fallback");
        assertTrue(cleaned.length() <= Beacon.MAX_NAME_LENGTH,
                "got " + cleaned.length() + " characters");
    }

    @Test
    void aMarkIsNamedAfterWhereItStandsUntilSomebodyNamesIt() {
        // More use in a list of several than "New Beacon" three times over.
        assertEquals("Mark 120, -340", Beacon.defaultName(new BlockPos(120, 63, -340)));
    }

    @Test
    void aMarkSurvivesBeingWrittenAndReadBack() {
        Beacon original = new Beacon(UUID.randomUUID(), "North Channel",
                "minecraft:overworld", new BlockPos(10, 62, -20));
        assertEquals(original, Beacon.load(original.save()));
    }
}
