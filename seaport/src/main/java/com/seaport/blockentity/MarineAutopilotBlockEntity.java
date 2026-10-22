package com.seaport.blockentity;

import com.seaport.data.Beacon;
import com.seaport.data.PortDirectory;
import com.seaport.data.VoyageSchedule;
import com.seaport.network.OpenMarineAutopilotPayload;
import com.seaport.registry.SeaPortBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Backs one Marine Autopilot: the orders a vessel is sailing under.
 *
 * Unlike the Port Station and the channel markers, this keeps its state in
 * its own tag rather than in a world registry, and for the opposite reason.
 * A port is a destination other ships look up, so it has to be readable with
 * its chunks unloaded. A schedule belongs to the vessel carrying it, moves
 * with it, and is only ever read by the autopilot itself - which is aboard.
 *
 * Nothing here sails yet. The schedule, the callsign and the running flag
 * are the state a helm needs; turning that into a course across charted
 * water, and into the small corrections that keep a hull off a sandbar and
 * out of another ship, is the next piece. Keeping the two apart means the
 * orders can be written, saved, reloaded and checked before anything moves,
 * which is a far easier thing to get right than both at once.
 */
public class MarineAutopilotBlockEntity extends BlockEntity {

    /** Same cap as a berth name: these end up in the same lists. */
    public static final int MAX_CALLSIGN_LENGTH = 24;

    private String callsign = "";
    private VoyageSchedule schedule = new VoyageSchedule();

    /**
     * Whether the vessel is working its schedule.
     *
     * Separate from having one, because stopping a ship should not throw
     * away where it was going. A vessel halted mid-voyage keeps its cursor,
     * so starting it again sends it on to the stop it was already making
     * for rather than back to the top of the list.
     */
    private boolean running;

    public MarineAutopilotBlockEntity(BlockPos pos, BlockState state) {
        super(SeaPortBlockEntities.MARINE_AUTOPILOT.get(), pos, state);
    }

    public String callsign() {
        return callsign;
    }

    public void setCallsign(String requested) {
        // Cleaned the same way a mark's name is, and for the same reason:
        // this arrives in a packet, and it is shown in lists next to other
        // people's ships.
        callsign = Beacon.cleanName(requested, "");
        setChanged();
    }

    public VoyageSchedule schedule() {
        return schedule;
    }

    public void setSchedule(VoyageSchedule updated) {
        this.schedule = updated;
        setChanged();
    }

    public boolean running() {
        return running;
    }

    public void setRunning(boolean value) {
        running = value;
        setChanged();
    }

    public void openScreen(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        PacketDistributor.sendToPlayer(player, new OpenMarineAutopilotPayload(
                getBlockPos(), callsign, running, schedule, PortDirectory.snapshot(level)));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("callsign", callsign);
        tag.put("schedule", schedule.save());
        tag.putBoolean("running", running);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        callsign = tag.getString("callsign");
        running = tag.getBoolean("running");
        try {
            schedule = VoyageSchedule.load(tag.getCompound("schedule"));
        } catch (RuntimeException unreadable) {
            // An autopilot that will not load is a block that crashes the
            // chunk it is in, which takes the ship with it. An empty
            // schedule is recoverable; a chunk that will not load is not.
            schedule = new VoyageSchedule();
        }
    }
}
