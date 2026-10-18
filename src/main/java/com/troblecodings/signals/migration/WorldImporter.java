package com.troblecodings.signals.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.brigadier.context.CommandContext;
import com.troblecodings.signals.OpenSignalsMain;
import com.troblecodings.signals.blocks.Signal;
import com.troblecodings.signals.core.LoadHolder;
import com.troblecodings.signals.core.PathGetter;
import com.troblecodings.signals.core.SignalAngel;
import com.troblecodings.signals.core.SignalStateLoadHoler;
import com.troblecodings.signals.handler.SignalStateHandler;
import com.troblecodings.signals.handler.SignalStateInfo;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.ListNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.state.Property;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.FolderName;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Imports the data written by the 1.12 version of the mod (WorldExporter there).
 *
 * 1. When a world is loaded and [world folder]/osexport/osfiles exists, the mod files of that
 * dimension (signal states, names, signalbox data) are copied to the place where this version
 * looks for them. This happens before the other handlers read their files. It is done only once
 * per dimension.
 *
 * 2. The command "/opensignals import" places all exported blocks again and fills their tile
 * entities with the exported data. The chunks are processed a few per tick, so big worlds do
 * not freeze the server.
 */
public final class WorldImporter {

    public static final String EXPORT_FOLDER = "osexport";
    public static final String EXPORT_FILE = "opensignals_export.dat";
    private static final String BLOCKS_MARKER = "blocks_imported";
    private static final String FILES_MARKER = "files_imported_";
    private static final String[] FILE_DIRECTORIES = new String[] {
            "signalfiles", "namefiles", "signalboxhandlerfiles"
    };

    /**
     * In 1.12 the signal rotation ("angel") runs the other way round than in 1.16. Both versions
     * place a signal with the same player rotation, but the ordinal is mirrored. If all imported
     * signals are turned mirrored in game, set this to false.
     */
    public static final boolean MIRROR_SIGNAL_ANGLE = true;

    private static final int CHUNKS_PER_TICK = 2;
    private static final int FLAGS = 2 | 16;

    // key = name in 1.16, value = name in 1.12
    private static final Map<String, String> PROPERTY_ALIASES = new HashMap<>();

    static {
        PROPERTY_ALIASES.put("powerd", "powered");
    }

    private static final Deque<ChunkTask> TASKS = new ArrayDeque<>();
    private static ImportState current = null;

    private WorldImporter() {
    }

    private static final class ChunkTask {

        private final String dimension;
        private final int chunkX;
        private final int chunkZ;
        private final List<CompoundNBT> entries = new ArrayList<>();

        private ChunkTask(final String dimension, final int chunkX, final int chunkZ) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private static final class ImportState {

        private final CommandSource source;
        private final MinecraftServer server;
        private final Path markerFile;
        private final int totalChunks;
        private int placed = 0;
        private int tiles = 0;
        private int tilesMissing = 0;
        private int failed = 0;
        private int missingDimension = 0;
        private final Set<String> unknownBlocks = new LinkedHashSet<>();

        private ImportState(final CommandSource source, final MinecraftServer server,
                final Path markerFile, final int totalChunks) {
            this.source = source;
            this.server = server;
            this.markerFile = markerFile;
            this.totalChunks = totalChunks;
        }
    }

    // ------------------------------------------------------------------
    // Part 1: copy the mod files to the new locations
    // ------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWorldLoad(final WorldEvent.Load event) {
        if (!(event.getWorld() instanceof ServerWorld)) {
            return;
        }
        final ServerWorld world = (ServerWorld) event.getWorld();
        final String key = dimensionName(world.dimension());
        if (key == null) {
            return;
        }
        final Path exportDir = world.getServer().getWorldPath(FolderName.ROOT)
                .resolve(EXPORT_FOLDER);
        final Path marker = exportDir.resolve(FILES_MARKER + key);
        final Path source = exportDir.resolve("osfiles");
        if (Files.exists(marker) || !Files.isDirectory(source)) {
            return;
        }
        try {
            for (final String directory : FILE_DIRECTORIES) {
                final Path from = source.resolve(directory).resolve(key);
                if (!Files.exists(from)) {
                    continue;
                }
                final Path to = PathGetter.getNewPathForFiles(world, directory);
                deleteRecursively(to);
                copyRecursively(from, to);
                OpenSignalsMain.getLogger().info("Imported OpenSignals files " + from + " -> " + to);
            }
            Files.write(marker, "done".getBytes(StandardCharsets.UTF_8));
        } catch (final IOException ex) {
            OpenSignalsMain.getLogger().error("Could not import the OpenSignals files of "
                    + key + "!", ex);
        }
    }

    // ------------------------------------------------------------------
    // Part 2: the command and the block import
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("opensignals").requires(source -> source.hasPermission(4))
                        .then(Commands.literal("import")
                                .executes(context -> startImport(context, false))
                                .then(Commands.literal("force")
                                        .executes(context -> startImport(context, true)))));
    }

    private static int startImport(final CommandContext<CommandSource> context,
            final boolean force) {
        final CommandSource source = context.getSource();
        final MinecraftServer server = source.getServer();
        if (current != null) {
            source.sendFailure(new StringTextComponent(
                    "[OpenSignals] An import is already running."));
            return 0;
        }
        final Path exportDir = server.getWorldPath(FolderName.ROOT).resolve(EXPORT_FOLDER);
        final Path exportFile = exportDir.resolve(EXPORT_FILE);
        final Path marker = exportDir.resolve(BLOCKS_MARKER);
        if (!Files.exists(exportFile)) {
            source.sendFailure(new StringTextComponent("[OpenSignals] No export found. Expected: "
                    + exportFile.toAbsolutePath()));
            return 0;
        }
        if (Files.exists(marker) && !force) {
            source.sendFailure(new StringTextComponent("[OpenSignals] The blocks of this world "
                    + "were already imported. Use '/opensignals import force' to import again "
                    + "(this overwrites everything at the exported positions)."));
            return 0;
        }
        final CompoundNBT root;
        try (InputStream in = Files.newInputStream(exportFile)) {
            root = CompressedStreamTools.readCompressed(in);
        } catch (final IOException ex) {
            OpenSignalsMain.getLogger().error("Could not read the export file!", ex);
            source.sendFailure(new StringTextComponent("[OpenSignals] Could not read the export "
                    + "file: " + ex));
            return 0;
        }
        final Map<String, ChunkTask> grouped = new LinkedHashMap<>();
        final ListNBT blocks = root.getList("blocks", 10);
        for (int i = 0; i < blocks.size(); i++) {
            final CompoundNBT entry = blocks.getCompound(i);
            final String dimension = entry.getString("dim");
            final int chunkX = entry.getInt("x") >> 4;
            final int chunkZ = entry.getInt("z") >> 4;
            grouped.computeIfAbsent(dimension + ":" + ChunkPos.asLong(chunkX, chunkZ),
                    _u -> new ChunkTask(dimension, chunkX, chunkZ)).entries.add(entry);
        }
        TASKS.clear();
        TASKS.addAll(grouped.values());
        current = new ImportState(source, server, marker, TASKS.size());
        source.sendSuccess(new StringTextComponent("[OpenSignals] Import started: "
                + blocks.size() + " blocks in " + TASKS.size() + " chunks."), true);
        if (TASKS.isEmpty()) {
            finish();
        }
        return 1;
    }

    @SubscribeEvent
    public static void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || current == null) {
            return;
        }
        for (int i = 0; i < CHUNKS_PER_TICK && !TASKS.isEmpty(); i++) {
            final ChunkTask task = TASKS.poll();
            try {
                processTask(current, task);
            } catch (final Exception ex) {
                current.failed += task.entries.size();
                OpenSignalsMain.getLogger().error("Could not import chunk " + task.chunkX + ", "
                        + task.chunkZ + " of " + task.dimension + "!", ex);
            }
        }
        final int done = current.totalChunks - TASKS.size();
        if (!TASKS.isEmpty() && done % 200 < CHUNKS_PER_TICK) {
            current.source.sendSuccess(new StringTextComponent("[OpenSignals] Import: " + done
                    + " / " + current.totalChunks + " chunks..."), false);
        }
        if (TASKS.isEmpty()) {
            finish();
        }
    }

    private static void processTask(final ImportState state, final ChunkTask task) {
        final RegistryKey<World> key = dimensionKey(task.dimension);
        final ServerWorld world = key == null ? null : state.server.getLevel(key);
        if (world == null) {
            state.missingDimension += task.entries.size();
            return;
        }
        // loads the chunk (it exists, because the blocks were in it)
        world.getChunk(task.chunkX, task.chunkZ);
        final List<CompoundNBT> withTile = new ArrayList<>();
        for (final CompoundNBT entry : task.entries) {
            final ResourceLocation name = new ResourceLocation(entry.getString("block"));
            if (!ForgeRegistries.BLOCKS.containsKey(name)) {
                state.unknownBlocks.add(name.toString());
                continue;
            }
            final Block block = ForgeRegistries.BLOCKS.getValue(name);
            final BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"),
                    entry.getInt("z"));
            try {
                world.setBlock(pos, buildState(block, entry), FLAGS);
                state.placed++;
                if (entry.contains("te")) {
                    withTile.add(entry);
                }
            } catch (final RuntimeException ex) {
                state.failed++;
                OpenSignalsMain.getLogger().error("Could not place " + name + " at " + pos + "!",
                        ex);
            }
        }
        final List<BlockPos> signalPositions = new ArrayList<>();
        for (final CompoundNBT entry : withTile) {
            final BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"),
                    entry.getInt("z"));
            final TileEntity tile = world.getBlockEntity(pos);
            if (tile == null) {
                state.tilesMissing++;
                continue;
            }
            try {
                final CompoundNBT data = entry.getCompound("te").copy();
                data.putInt("x", pos.getX());
                data.putInt("y", pos.getY());
                data.putInt("z", pos.getZ());
                final BlockState blockState = world.getBlockState(pos);
                tile.load(blockState, data);
                tile.setChanged();
                world.sendBlockUpdated(pos, blockState, blockState, 3);
                state.tiles++;
                if (blockState.getBlock() instanceof Signal) {
                    signalPositions.add(pos);
                }
            } catch (final RuntimeException ex) {
                state.failed++;
                OpenSignalsMain.getLogger().error("Could not load the tile entity at " + pos
                        + "!", ex);
            }
        }
        resendSignalStates(world, signalPositions);
    }

    /**
     * The signal states are only sent to a client when it starts to watch a chunk. The imported
     * signals are placed in chunks that are already watched, so the states have to be loaded and
     * sent again. Otherwise the signals look like they have no configuration until the chunk is
     * reloaded.
     */
    private static void resendSignalStates(final ServerWorld world,
            final List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return;
        }
        for (final PlayerEntity player : world.players()) {
            final List<SignalStateLoadHoler> holders = new ArrayList<>();
            for (final BlockPos pos : positions) {
                final Block block = world.getBlockState(pos).getBlock();
                if (block instanceof Signal) {
                    holders.add(new SignalStateLoadHoler(
                            new SignalStateInfo(world, pos, (Signal) block),
                            new LoadHolder<>(player)));
                }
            }
            SignalStateHandler.loadSignals(holders, player);
        }
    }

    private static BlockState buildState(final Block block, final CompoundNBT entry) {
        final CompoundNBT properties = entry.getCompound("props");
        final CompoundNBT ordinals = entry.getCompound("ordinals");
        BlockState state = block.defaultBlockState();
        for (final Property<?> property : block.defaultBlockState().getProperties()) {
            if (property == Signal.ANGEL) {
                if (ordinals.contains("angel")) {
                    final int old = ordinals.getInt("angel");
                    final int index = MIRROR_SIGNAL_ANGLE ? (16 - old) & 15 : old & 15;
                    state = state.setValue(Signal.ANGEL, SignalAngel.values()[index]);
                }
                continue;
            }
            state = applyProperty(state, property, properties, ordinals);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(final BlockState state,
            final Property<T> property, final CompoundNBT properties,
            final CompoundNBT ordinals) {
        String key = property.getName();
        if (!properties.contains(key)) {
            key = PROPERTY_ALIASES.getOrDefault(key, key);
            if (!properties.contains(key)) {
                return state;
            }
        }
        Optional<T> value = property.getValue(properties.getString(key));
        if (!value.isPresent() && ordinals.contains(key)) {
            final List<T> values = new ArrayList<>(property.getPossibleValues());
            final int ordinal = ordinals.getInt(key);
            if (ordinal >= 0 && ordinal < values.size()) {
                value = Optional.of(values.get(ordinal));
            }
        }
        if (!value.isPresent()) {
            return state;
        }
        return state.setValue(property, value.get());
    }

    private static void finish() {
        final ImportState state = current;
        current = null;
        TASKS.clear();
        try {
            Files.write(state.markerFile, "done".getBytes(StandardCharsets.UTF_8));
        } catch (final IOException ex) {
            OpenSignalsMain.getLogger().error("Could not write the import marker!", ex);
        }
        final CommandSource source = state.source;
        source.sendSuccess(new StringTextComponent("[OpenSignals] Import finished: "
                + state.placed + " blocks placed, " + state.tiles + " tile entities filled."),
                true);
        if (state.tilesMissing > 0 || state.failed > 0 || state.missingDimension > 0) {
            source.sendSuccess(new StringTextComponent("[OpenSignals] WARNING: "
                    + state.tilesMissing + " tile entities missing, " + state.failed
                    + " errors, " + state.missingDimension
                    + " blocks in unknown dimensions (see log)."), true);
        }
        if (!state.unknownBlocks.isEmpty()) {
            final List<String> names = state.unknownBlocks.stream().limit(10)
                    .collect(Collectors.toList());
            source.sendSuccess(new StringTextComponent("[OpenSignals] WARNING: "
                    + state.unknownBlocks.size() + " block types do not exist in this version "
                    + "and were skipped: " + String.join(", ", names)
                    + (state.unknownBlocks.size() > names.size() ? ", ..." : "")), true);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String dimensionName(final RegistryKey<World> key) {
        if (key == World.OVERWORLD) {
            return "overworld";
        } else if (key == World.NETHER) {
            return "the_nether";
        } else if (key == World.END) {
            return "the_end";
        }
        return null;
    }

    private static RegistryKey<World> dimensionKey(final String name) {
        switch (name) {
            case "overworld":
                return World.OVERWORLD;
            case "the_nether":
                return World.NETHER;
            case "the_end":
                return World.END;
            default:
                return null;
        }
    }

    private static void copyRecursively(final Path from, final Path to) throws IOException {
        final List<Path> paths;
        try (Stream<Path> stream = Files.walk(from)) {
            paths = stream.collect(Collectors.toList());
        }
        for (final Path path : paths) {
            final Path destination = to.resolve(from.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                if (destination.getParent() != null) {
                    Files.createDirectories(destination.getParent());
                }
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteRecursively(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        final List<Path> paths;
        try (Stream<Path> stream = Files.walk(path)) {
            paths = stream.collect(Collectors.toList());
        }
        Collections.sort(paths, Comparator.reverseOrder());
        for (final Path entry : paths) {
            Files.delete(entry);
        }
    }
}
