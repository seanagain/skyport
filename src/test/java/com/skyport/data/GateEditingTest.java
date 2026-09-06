package com.skyport.data;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renaming and reordering stands.
 *
 * Worth testing because both operations are one careless line away from
 * silently rearranging an airport. Gates live in a LinkedHashMap, and the
 * obvious way to rename a key - remove it, put it back - moves that gate to
 * the end of the list. The order is what the Autopilot's gate picker runs
 * through, so renaming Gate A would quietly reshuffle every stand after it
 * and nothing would look wrong until an aircraft went to the wrong one.
 */
class GateEditingTest {

    private static AirportLayout layoutWithGates(String... names) {
        AirportLayout layout = new AirportLayout(
                UUID.randomUUID(), "Test", ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld")));
        int x = 0;
        for (String name : names) layout.gates().put(name, new BlockPos(x += 10, 64, 0));
        return layout;
    }

    private static List<String> order(AirportLayout layout) {
        return List.copyOf(layout.gates().keySet());
    }

    @Test
    void renamingKeepsThePositionInTheList() {
        AirportLayout layout = layoutWithGates("Gate A", "Gate B", "Gate C");

        assertTrue(layout.renameGate("Gate A", "Cargo"));

        assertEquals(List.of("Cargo", "Gate B", "Gate C"), order(layout),
                "the renamed gate must stay first, not move to the end");
    }

    @Test
    void renamingKeepsThePosition() {
        AirportLayout layout = layoutWithGates("Gate A", "Gate B");
        BlockPos before = layout.gates().get("Gate B");

        layout.renameGate("Gate B", "Stand 2");

        assertEquals(before, layout.gates().get("Stand 2"));
        assertFalse(layout.gates().containsKey("Gate B"));
    }

    /** Two stands with one name would merge them, and the second would
     *  silently inherit the first one's position. */
    @Test
    void aNameAlreadyInUseIsRefused() {
        AirportLayout layout = layoutWithGates("Gate A", "Gate B");

        assertFalse(layout.renameGate("Gate A", "Gate B"));
        assertEquals(List.of("Gate A", "Gate B"), order(layout), "nothing should have changed");
        assertEquals(2, layout.gates().size());
    }

    @Test
    void blankNamesAreRefused() {
        AirportLayout layout = layoutWithGates("Gate A");

        assertFalse(layout.renameGate("Gate A", "   "));
        assertFalse(layout.renameGate("Gate A", null));
        assertEquals(List.of("Gate A"), order(layout));
    }

    /** Renaming a gate to what it is already called is a no-op the player can
     *  easily trigger by clicking into the box and out again. */
    @Test
    void renamingToTheSameNameIsHarmless() {
        AirportLayout layout = layoutWithGates("Gate A", "Gate B");

        assertTrue(layout.renameGate("Gate A", "Gate A"));
        assertEquals(List.of("Gate A", "Gate B"), order(layout));
    }

    @Test
    void gatesMoveUpAndDownTheList() {
        AirportLayout layout = layoutWithGates("Gate A", "Gate B", "Gate C");

        assertTrue(layout.moveGate("Gate C", -1));
        assertEquals(List.of("Gate A", "Gate C", "Gate B"), order(layout));

        assertTrue(layout.moveGate("Gate C", 1));
        assertEquals(List.of("Gate A", "Gate B", "Gate C"), order(layout));
    }

    /** The ends of the list are ends, not wrap-arounds - a player holding the
     *  up button should not find the top gate appearing at the bottom. */
    @Test
    void movingPastTheEndDoesNothing() {
        AirportLayout layout = layoutWithGates("Gate A", "Gate B");

        assertFalse(layout.moveGate("Gate A", -1));
        assertFalse(layout.moveGate("Gate B", 1));
        assertEquals(List.of("Gate A", "Gate B"), order(layout));
    }

    @Test
    void reorderingKeepsEveryGateAndItsPosition() {
        AirportLayout layout = layoutWithGates("Gate A", "Gate B", "Gate C");
        BlockPos a = layout.gates().get("Gate A");

        layout.moveGate("Gate A", 2);

        assertEquals(List.of("Gate B", "Gate C", "Gate A"), order(layout));
        assertEquals(3, layout.gates().size());
        assertEquals(a, layout.gates().get("Gate A"), "moving a gate must not move it in the world");
    }

    @Test
    void editingAGateThatIsNotThereIsRefused() {
        AirportLayout layout = layoutWithGates("Gate A");

        assertFalse(layout.renameGate("Gate Z", "Gate B"));
        assertFalse(layout.moveGate("Gate Z", 1));
        assertEquals(List.of("Gate A"), order(layout));
    }
}
