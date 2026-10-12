package com.portcore.survey;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Keeps a dimension's survey up to date, a little at a time, forever.
 *
 * Three ways work arrives, and they cost wildly different amounts:
 *
 * A chunk being LOADED is free. Its heightmaps are already built and the chunk
 * was going to be in memory regardless, so this is pure profit and it happens
 * inline.
 *
 * A chunk that exists on DISK costs a file read. That read happens off the
 * server thread - it is I/O and NBT parsing, neither of which needs the game -
 * and only the finished result comes back to be filed. A few per tick, no
 * more.
 *
 * A chunk that was never GENERATED costs terrain generation, so it never
 * happens. Unsurveyed water stays unsurveyed, which the chart says plainly
 * and the router treats as a wall.
 *
 * The thread rule is the one worth stating, because breaking it produces the
 * kind of fault that shows up once a week and never while anybody is looking:
 * NOTHING off the server thread touches the store, the level or the world.
 * The background half reads bytes and turns them into sixteen numbers, which
 * is pure arithmetic; the numbers cross back over a queue and are filed on the
 * server thread, during the tick, by {@link #tick}.
 */
public final class ChunkSurveyor {

    /** One per dimension: a chart of the Nether is not a chart of the sea. */
    private static final Map<ServerLevel, ChunkSurveyor> BY_LEVEL = new ConcurrentHashMap<>();

    /**
     * How many disk reads may be outstanding at once.
     *
     * Small deliberately. The backlog is not urgent - it is filling in a world
     * that has been sitting there for a year - and the cost of being wrong
     * upward is competing with the server for its own disk.
     */
    private final int concurrentReads;
    /** How many finished surveys are filed per tick. Filing is cheap; this
     *  mostly stops a burst of completions landing in one tick. */
    private final int filedPerTick;

    private final ServerLevel level;
    private final SurveyBacklog backlog = new SurveyBacklog();
    private final ConcurrentLinkedQueue<Finished> finished = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();

    /** Regions the listing found, for the line that reports it. */
    private int regionsFound;
    /** Chunks the region headers say exist - the real size of the job, as
     *  opposed to the slots that merely could have held one. */
    private int chunksOnDisk;
    private boolean enumeratedRegions;
    private long surveyedFromDisk;
    private long surveyedFromMemory;
    private long skippedUnfinished;

    private record Finished(int chunkX, int chunkZ, CellSurvey @Nullable [] cells) { }

    private ChunkSurveyor(ServerLevel level, int concurrentReads, int filedPerTick) {
        this.level = level;
        this.concurrentReads = Math.max(1, concurrentReads);
        this.filedPerTick = Math.max(1, filedPerTick);
    }

    public static ChunkSurveyor of(ServerLevel level, int concurrentReads, int filedPerTick) {
        return BY_LEVEL.computeIfAbsent(level, l -> new ChunkSurveyor(l, concurrentReads, filedPerTick));
    }

    @Nullable
    public static ChunkSurveyor existing(ServerLevel level) {
        return BY_LEVEL.get(level);
    }

    public static void forget(ServerLevel level) {
        BY_LEVEL.remove(level);
    }

    // ---- the free route --------------------------------------------------

    /**
     * Survey a chunk that is in memory anyway.
     *
     * Called from the chunk-load event, so it runs on the server thread and
     * can file its result directly.
     */
    public void surveyLoaded(ChunkAccess chunk) {
        CellSurvey[] cells = WorldSurvey.fromLoaded(chunk);
        if (cells == null) return;

        ChunkPos pos = chunk.getPos();
        SurveyStore.get(level).recordChunk(pos.x, pos.z, cells, level.getGameTime());
        surveyedFromMemory++;
        // It may have been queued for a disk read before the game loaded it.
        // It is answered now, and answered better - from memory rather than a
        // file - so reading it would spend an I/O to learn the same thing.
        backlog.drop(RegionFiles.chunkKey(pos.x, pos.z));
    }

    /** The world changed here, so what was known about it is no longer known. */
    public void invalidate(int chunkX, int chunkZ) {
        SurveyStore.get(level).forgetChunk(chunkX, chunkZ);
        backlog.offerUrgent(RegionFiles.chunkKey(chunkX, chunkZ));
    }

    // ---- the backlog -----------------------------------------------------

    /**
     * Find every chunk the world has ever generated, from the names of its
     * region files.
     *
     * Done once, and only once: the listing is a directory read rather than a
     * world query, but it is still a directory with potentially thousands of
     * entries and there is no reason to keep asking. New chunks arrive through
     * the load event instead, which is free.
     */
    public void enumerateRegions() {
        if (enumeratedRegions) return;
        enumeratedRegions = true;

        Path regions = regionFolder();
        if (regions == null || !Files.isDirectory(regions)) return;

        try (Stream<Path> files = Files.list(regions)) {
            files.forEach(file -> {
                RegionFiles.Region region = RegionFiles.parse(file.getFileName().toString());
                if (region == null) return;
                regionsFound++;

                // Ask the file which of its thousand slots hold anything,
                // rather than queueing all of them and finding out by reading.
                // A region around a spawn point can hold fifty chunks in room
                // for a thousand, and the difference is a thousand futile disk
                // reads per region - which is exactly what the first run of
                // this did: four thousand reads to find forty-nine chunks.
                for (int slot : presentSlots(file)) {
                    int x = RegionFiles.slotChunkX(region, slot);
                    int z = RegionFiles.slotChunkZ(region, slot);
                    chunksOnDisk++;
                    // A chunk already surveyed is not re-read. On a second
                    // start this skips the lot, which is the difference
                    // between a one-off cost and a permanent one.
                    if (SurveyStore.get(level).hasChunk(x, z)) continue;
                    backlog.offer(RegionFiles.chunkKey(x, z));
                }
            });
        } catch (IOException cannotList) {
            // A world on a read-only mount, or a path that is not there. The
            // survey simply fills in from chunk loads instead, which is slower
            // and perfectly correct.
            enumeratedRegions = true;
        }
    }

    /**
     * The header of one region file, or nothing if it cannot be read.
     *
     * One four-kilobyte read per region during level load, instead of a
     * thousand chunk reads per region afterwards. A file that is locked,
     * truncated or gone between the listing and this read contributes no
     * chunks, which is the same outcome as it not being there.
     */
    private int[] presentSlots(Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            return RegionFiles.presentSlots(in.readNBytes(RegionFiles.HEADER_BYTES));
        } catch (IOException unreadable) {
            return new int[0];
        }
    }

    @Nullable
    private Path regionFolder() {
        try {
            Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
            return DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region");
        } catch (RuntimeException noSuchPath) {
            return null;
        }
    }

    /** Somebody is here, so this chunk matters more than a region file from
     *  last year. */
    public void prioritise(int chunkX, int chunkZ) {
        if (SurveyStore.get(level).hasChunk(chunkX, chunkZ)) return;
        backlog.offerUrgent(RegionFiles.chunkKey(chunkX, chunkZ));
    }

    // ---- the tick --------------------------------------------------------

    /**
     * File whatever came back, then start a few more reads.
     *
     * Results first on purpose: a read that has finished has already cost its
     * disk access, and leaving it queued while starting new ones would grow
     * the queue for no gain.
     */
    public void tick() {
        int filed = 0;
        Finished result;
        while (filed < filedPerTick && (result = finished.poll()) != null) {
            if (result.cells() != null) {
                SurveyStore.get(level).recordChunk(
                        result.chunkX(), result.chunkZ(), result.cells(), level.getGameTime());
                surveyedFromDisk++;
            } else {
                skippedUnfinished++;
            }
            filed++;
        }

        int room = concurrentReads - inFlight.get();
        if (room <= 0) return;
        List<Long> next = backlog.take(room);
        for (long key : next) startRead(RegionFiles.chunkX(key), RegionFiles.chunkZ(key));
    }

    /**
     * Ask for a chunk's bytes, and summarise them wherever that future
     * happens to complete.
     *
     * The only thing the callback touches is the NBT it was handed and a
     * concurrent queue. It reads no blocks, no level and no store, because it
     * is not on the server thread and has no business doing any of that.
     */
    private void startRead(int chunkX, int chunkZ) {
        inFlight.incrementAndGet();
        int minY = level.getMinBuildHeight();
        try {
            level.getChunkSource().chunkMap.read(new ChunkPos(chunkX, chunkZ))
                    .whenComplete((maybeNbt, failure) -> {
                        try {
                            if (failure != null || maybeNbt == null || maybeNbt.isEmpty()) {
                                finished.add(new Finished(chunkX, chunkZ, null));
                                return;
                            }
                            CompoundTag nbt = maybeNbt.get();
                            // Half-generated chunks describe a world about to
                            // change; something will load them to finish them,
                            // and the free route catches them then.
                            if (!WorldSurvey.isFullyGenerated(nbt)) {
                                finished.add(new Finished(chunkX, chunkZ, null));
                                return;
                            }
                            finished.add(new Finished(chunkX, chunkZ, WorldSurvey.fromNbt(nbt, minY)));
                        } catch (RuntimeException unreadable) {
                            // One corrupt chunk must not stop the survey, and
                            // must not take the IO thread down with it.
                            finished.add(new Finished(chunkX, chunkZ, null));
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    });
        } catch (RuntimeException cannotRead) {
            inFlight.decrementAndGet();
        }
    }

    // ---- what it has been doing ------------------------------------------

    public record Progress(int backlog, int inFlight, long fromMemory, long fromDisk,
                           long skipped, long dropped, int chunksKnown, boolean enumerated,
                           int regionsFound, int chunksOnDisk) { }

    public Progress progress() {
        return new Progress(backlog.size(), inFlight.get(), surveyedFromMemory, surveyedFromDisk,
                skippedUnfinished, backlog.droppedCount(),
                SurveyStore.get(level).chunkCount(), enumeratedRegions, regionsFound, chunksOnDisk);
    }

    /**
     * One line for the log.
     *
     * A background process with no output is one nobody can tell is working,
     * and this one's entire effect is a number per cell that never appears on
     * screen. Without a line in the log, "is it surveying" can only be
     * answered by standing in the world typing a command at it.
     */
    public String summary() {
        Progress p = progress();
        return String.format(
                "%s: %d chunks charted (%d as loaded, %d from disk), %d waiting, %d reading,"
                        + " %d regions on disk holding %d chunks%s",
                level.dimension().location(), p.chunksKnown(), p.fromMemory(), p.fromDisk(),
                p.backlog(), p.inFlight(), p.regionsFound(), p.chunksOnDisk(),
                p.skipped() > 0 ? ", " + p.skipped() + " unusable" : "");
    }
}
