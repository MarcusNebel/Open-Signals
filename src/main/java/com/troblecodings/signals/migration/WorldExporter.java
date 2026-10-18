package com.troblecodings.signals.migration;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.troblecodings.signals.OpenSignalsMain;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.ICommandSender;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraftforge.common.DimensionManager;

/**
 * Exports everything OpenSignals has put into a 1.12.2 world, so that it can be imported again
 * with the 1.16 version of the mod (see WorldImporter there).
 *
 * The export reads the saved region files (.mca) of the world directly. The block ids and
 * metadata in there are resolved with the running 1.12 registry, so the mod's own
 * getStateFromMeta does the decoding. Nothing has to be loaded into the running world.
 *
 * Result: [world folder]/osexport/opensignals_export.dat and a copy of [world
 * folder]/osfiles in [world folder]/osexport/osfiles.
 */
public final class WorldExporter {

    public static final String EXPORT_FOLDER = "osexport";
    public static final String EXPORT_FILE = "opensignals_export.dat";
    public static final int FORMAT_VERSION = 1;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private WorldExporter() {
    }

    private static final class DimensionFolder {

        private final File folder;
        private final String key;

        private DimensionFolder(final File folder, final String key) {
            this.folder = folder;
            this.key = key;
        }
    }

    private static final class Context {

        // 0 = not checked yet, 1 = not an OpenSignals block, 2 = OpenSignals block
        private final byte[] blockCache = new byte[4096];
        private int blocks = 0;
        private int blocksWithTile = 0;
        private int skippedBlocks = 0;
        private int scannedChunks = 0;
        private int failedChunks = 0;
        private int modItemsInChunks = 0;
        private int modItemsInPlayerData = 0;
    }

    /**
     * Starts the export. Has to be called on the server thread.
     */
    public static void start(final MinecraftServer server, final ICommandSender sender) {
        if (!RUNNING.compareAndSet(false, true)) {
            send(server, sender, "[OpenSignals] An export is already running.");
            return;
        }
        final Map<WorldServer, Boolean> savingStates = new HashMap<>();
        try {
            final File worldDir = server.getWorld(0).getSaveHandler().getWorldDirectory();
            final List<DimensionFolder> dimensions = collectDimensions(worldDir);
            send(server, sender, "[OpenSignals] Saving the world before the export...");
            server.getPlayerList().saveAllPlayerData();
            server.saveAllWorlds(false);
            for (final WorldServer world : DimensionManager.getWorlds()) {
                world.flush();
                savingStates.put(world, world.disableLevelSaving);
                world.disableLevelSaving = true;
            }
            send(server, sender, "[OpenSignals] Export started. This can take a while on big "
                    + "worlds. Please do not build or change anything until it is finished.");
            final Thread thread = new Thread(
                    () -> runExport(server, sender, worldDir, dimensions, savingStates),
                    "OpenSignals-Export");
            thread.setDaemon(true);
            thread.start();
        } catch (final Exception ex) {
            OpenSignalsMain.getLogger().error("Could not start the export!", ex);
            send(server, sender, "[OpenSignals] Could not start the export: " + ex);
            restoreSaving(savingStates);
            RUNNING.set(false);
        }
    }

    private static void runExport(final MinecraftServer server, final ICommandSender sender,
            final File worldDir, final List<DimensionFolder> dimensions,
            final Map<WorldServer, Boolean> savingStates) {
        try {
            final long startTime = System.currentTimeMillis();
            final Context context = new Context();
            final NBTTagList blocks = new NBTTagList();
            for (final DimensionFolder dimension : dimensions) {
                exportDimension(dimension, blocks, context);
            }
            countPlayerItems(worldDir, context);

            final File exportDir = new File(worldDir, EXPORT_FOLDER);
            exportDir.mkdirs();

            final NBTTagCompound root = new NBTTagCompound();
            root.setInteger("version", FORMAT_VERSION);
            root.setString("minecraft", "1.12.2");
            root.setTag("blocks", blocks);
            final NBTTagCompound report = new NBTTagCompound();
            report.setInteger("blocks", context.blocks);
            report.setInteger("blocksWithTile", context.blocksWithTile);
            report.setInteger("skippedBlocks", context.skippedBlocks);
            report.setInteger("failedChunks", context.failedChunks);
            report.setInteger("modItemsInChunks", context.modItemsInChunks);
            report.setInteger("modItemsInPlayerData", context.modItemsInPlayerData);
            root.setTag("report", report);

            final Path target = new File(exportDir, EXPORT_FILE).toPath();
            final Path temp = new File(exportDir, EXPORT_FILE + ".tmp").toPath();
            try (OutputStream out = Files.newOutputStream(temp)) {
                CompressedStreamTools.writeCompressed(root, out);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

            copyOsFiles(worldDir, exportDir);

            final long seconds = (System.currentTimeMillis() - startTime) / 1000;
            send(server, sender, "[OpenSignals] Export finished in " + seconds + "s: "
                    + context.blocks + " blocks (" + context.blocksWithTile
                    + " with tile entity data) from " + context.scannedChunks + " chunks.");
            if (context.skippedBlocks > 0 || context.failedChunks > 0) {
                send(server, sender, "[OpenSignals] WARNING: " + context.skippedBlocks
                        + " blocks and " + context.failedChunks
                        + " chunks could not be read (see log).");
            }
            send(server, sender,
                    "[OpenSignals] OpenSignals items found: " + context.modItemsInChunks
                            + " in chunks (chests, item frames, ...), "
                            + context.modItemsInPlayerData + " in player data. "
                            + "Compare this with the 1.16 world after the import.");
            send(server, sender, "[OpenSignals] Copy the whole world folder (it contains the '"
                    + EXPORT_FOLDER + "' folder) to the 1.16 installation.");
        } catch (final Throwable throwable) {
            OpenSignalsMain.getLogger().error("The export failed!", throwable);
            send(server, sender, "[OpenSignals] The export failed: " + throwable);
        } finally {
            restoreSaving(savingStates);
            RUNNING.set(false);
        }
    }

    private static void restoreSaving(final Map<WorldServer, Boolean> savingStates) {
        savingStates.forEach((world, state) -> world.disableLevelSaving = state);
    }

    private static List<DimensionFolder> collectDimensions(final File worldDir) {
        final List<DimensionFolder> list = new ArrayList<>();
        list.add(new DimensionFolder(worldDir, "overworld"));
        list.add(new DimensionFolder(new File(worldDir, "DIM-1"), "the_nether"));
        list.add(new DimensionFolder(new File(worldDir, "DIM1"), "the_end"));
        for (final WorldServer world : DimensionManager.getWorlds()) {
            final String folder = world.provider.getSaveFolder();
            if (folder == null || "DIM-1".equals(folder) || "DIM1".equals(folder)) {
                continue;
            }
            list.add(new DimensionFolder(new File(worldDir, folder),
                    world.provider.getDimensionType().getName()));
        }
        return list;
    }

    private static void exportDimension(final DimensionFolder dimension, final NBTTagList out,
            final Context context) {
        final File[] files = new File(dimension.folder, "region")
                .listFiles((dir, name) -> name.startsWith("r.") && name.endsWith(".mca"));
        if (files == null) {
            return;
        }
        for (final File file : files) {
            final String[] parts = file.getName().split("\\.");
            if (parts.length != 4) {
                continue;
            }
            final int regionX;
            final int regionZ;
            try {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            } catch (final NumberFormatException ex) {
                continue;
            }
            for (int localZ = 0; localZ < 32; localZ++) {
                for (int localX = 0; localX < 32; localX++) {
                    final int chunkX = (regionX << 5) + localX;
                    final int chunkZ = (regionZ << 5) + localZ;
                    try (DataInputStream stream = RegionFileCache
                            .getChunkInputStream(dimension.folder, chunkX, chunkZ)) {
                        if (stream == null) {
                            continue;
                        }
                        context.scannedChunks++;
                        exportChunk(dimension, chunkX, chunkZ, CompressedStreamTools.read(stream),
                                out, context);
                    } catch (final Exception ex) {
                        context.failedChunks++;
                        OpenSignalsMain.getLogger().error("Could not read chunk " + chunkX + ", "
                                + chunkZ + " of " + dimension.key + "!", ex);
                    }
                }
            }
        }
    }

    private static void exportChunk(final DimensionFolder dimension, final int chunkX,
            final int chunkZ, final NBTTagCompound root, final NBTTagList out,
            final Context context) {
        if (!root.hasKey("Level", 10)) {
            return;
        }
        final NBTTagCompound level = root.getCompoundTag("Level");
        context.modItemsInChunks += countModItems(level);

        final Map<Long, NBTTagCompound> found = new HashMap<>();
        final NBTTagList sections = level.getTagList("Sections", 10);
        for (int i = 0; i < sections.tagCount(); i++) {
            final NBTTagCompound section = sections.getCompoundTagAt(i);
            final int baseY = section.getByte("Y") << 4;
            final byte[] ids = section.getByteArray("Blocks");
            final byte[] add = section.hasKey("Add", 7) ? section.getByteArray("Add") : null;
            final byte[] data = section.getByteArray("Data");
            if (ids.length < 4096) {
                continue;
            }
            for (int index = 0; index < 4096; index++) {
                int id = ids[index] & 0xFF;
                if (add != null) {
                    id |= nibble(add, index) << 8;
                }
                if (id == 0 || !isOpenSignalsBlock(id, context)) {
                    continue;
                }
                final int x = (chunkX << 4) + (index & 15);
                final int y = baseY + (index >> 8);
                final int z = (chunkZ << 4) + ((index >> 4) & 15);
                final NBTTagCompound entry = createEntry(dimension, id, nibble(data, index), x, y,
                        z);
                if (entry == null) {
                    context.skippedBlocks++;
                    continue;
                }
                found.put(new BlockPos(x, y, z).toLong(), entry);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        final NBTTagList tiles = level.getTagList("TileEntities", 10);
        for (int i = 0; i < tiles.tagCount(); i++) {
            final NBTTagCompound tile = tiles.getCompoundTagAt(i);
            final long position = new BlockPos(tile.getInteger("x"), tile.getInteger("y"),
                    tile.getInteger("z")).toLong();
            final NBTTagCompound entry = found.get(position);
            if (entry != null) {
                entry.setTag("te", tile.copy());
                context.blocksWithTile++;
            }
        }
        found.values().forEach(out::appendTag);
        context.blocks += found.size();
    }

    private static boolean isOpenSignalsBlock(final int id, final Context context) {
        byte cached = context.blockCache[id];
        if (cached == 0) {
            final Block block = Block.getBlockById(id);
            final ResourceLocation name = block == null ? null : block.getRegistryName();
            cached = name != null && OpenSignalsMain.MODID.equals(name.getResourceDomain())
                    ? (byte) 2
                    : (byte) 1;
            context.blockCache[id] = cached;
        }
        return cached == 2;
    }

    @SuppressWarnings({
            "unchecked", "rawtypes"
    })
    private static NBTTagCompound createEntry(final DimensionFolder dimension, final int id,
            final int meta, final int x, final int y, final int z) {
        try {
            final Block block = Block.getBlockById(id);
            final IBlockState state = block.getStateFromMeta(meta);
            final NBTTagCompound entry = new NBTTagCompound();
            entry.setString("dim", dimension.key);
            entry.setInteger("x", x);
            entry.setInteger("y", y);
            entry.setInteger("z", z);
            entry.setString("block", block.getRegistryName().toString());
            entry.setInteger("meta", meta);
            final NBTTagCompound properties = new NBTTagCompound();
            final NBTTagCompound ordinals = new NBTTagCompound();
            for (final Map.Entry<IProperty<?>, Comparable<?>> property : state.getProperties()
                    .entrySet()) {
                final IProperty rawProperty = property.getKey();
                final Comparable value = property.getValue();
                properties.setString(rawProperty.getName(), rawProperty.getName(value));
                if (value instanceof Enum) {
                    ordinals.setInteger(rawProperty.getName(), ((Enum<?>) value).ordinal());
                }
            }
            entry.setTag("props", properties);
            entry.setTag("ordinals", ordinals);
            return entry;
        } catch (final RuntimeException ex) {
            OpenSignalsMain.getLogger().error("Could not read block " + id + ":" + meta + " at "
                    + x + ", " + y + ", " + z + " in " + dimension.key + "!", ex);
            return null;
        }
    }

    private static int nibble(final byte[] array, final int index) {
        final int position = index >> 1;
        if (position >= array.length) {
            return 0;
        }
        return (index & 1) == 0 ? array[position] & 15 : (array[position] >> 4) & 15;
    }

    private static int countModItems(final NBTBase tag) {
        int count = 0;
        if (tag instanceof NBTTagCompound) {
            final NBTTagCompound compound = (NBTTagCompound) tag;
            if (compound.hasKey("id", 8) && compound.hasKey("Count")
                    && compound.getString("id").startsWith(OpenSignalsMain.MODID + ":")) {
                count += Math.max(1, compound.getByte("Count"));
            }
            for (final String key : compound.getKeySet()) {
                count += countModItems(compound.getTag(key));
            }
        } else if (tag instanceof NBTTagList) {
            final NBTTagList list = (NBTTagList) tag;
            for (int i = 0; i < list.tagCount(); i++) {
                count += countModItems(list.get(i));
            }
        }
        return count;
    }

    private static void countPlayerItems(final File worldDir, final Context context) {
        final File[] files = new File(worldDir, "playerdata")
                .listFiles((dir, name) -> name.endsWith(".dat"));
        if (files == null) {
            return;
        }
        for (final File file : files) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                context.modItemsInPlayerData += countModItems(CompressedStreamTools.readCompressed(in));
            } catch (final IOException ex) {
                OpenSignalsMain.getLogger().error("Could not read player data " + file + "!", ex);
            }
        }
    }

    private static void copyOsFiles(final File worldDir, final File exportDir)
            throws IOException {
        final Path source = new File(worldDir, "osfiles").toPath();
        if (!Files.isDirectory(source)) {
            return;
        }
        final Path target = new File(exportDir, "osfiles").toPath();
        deleteRecursively(target);
        final List<Path> paths;
        try (Stream<Path> stream = Files.walk(source)) {
            paths = stream.collect(Collectors.toList());
        }
        for (final Path path : paths) {
            final Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
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

    private static void send(final MinecraftServer server, final ICommandSender sender,
            final String message) {
        OpenSignalsMain.getLogger().info(message);
        server.addScheduledTask(() -> sender.sendMessage(new TextComponentString(message)));
    }
}
