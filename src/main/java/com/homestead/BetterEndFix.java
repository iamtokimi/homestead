package com.homestead;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.ServerCommandSource;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


@SuppressWarnings("NullableProblems")
public class BetterEndFix implements ModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("BetterEndFix");
    private static final String MARKER = "betterendfix.reset_end_island";

    @Override
    public void onInitialize() {
        try {
            LOGGER.info("[BetterEndFix] Scanning for worlds that need fixing…");
            List<SaveWorldInfo> toFix = scanForFixes();

            if (toFix.isEmpty()) {
                LOGGER.info("[BetterEndFix] No worlds need fixing.");
                return;
            }

            int fixed = 0;
            for (SaveWorldInfo info : toFix) {
                try {
                    applyFix(info);
                    fixed++;
                } catch (IOException e) {
                    LOGGER.error("[BetterEndFix] Failed to fix {}", info.worldDir, e);
                }
            }
            LOGGER.info("[BetterEndFix] Completed. {} world(s) fixed.", fixed);

            if (fixed > 0) {
                // Register listener to reset end island when End loads but only if we fixed something
                ServerWorldEvents.LOAD.register(this::onWorldLoad);
                LOGGER.info("[BetterEndFix] Registered End dimension load listener");
            }
        } catch (Throwable t) {
            LOGGER.error("[BetterEndFix] Unexpected error during initialization", t);
        }
    }

    private List<SaveWorldInfo> scanForFixes() {
        List<SaveWorldInfo> candidates = new ArrayList<>();
        Path gameDir = FabricLoader.getInstance().getGameDir();

        // dedicated server root world - look up level-name in server.properties
        Path props = gameDir.resolve("server.properties");
        if (Files.isRegularFile(props)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(props)) {
                p.load(in);
                String lvl = p.getProperty("level-name", "world").trim();
                checkAndAdd(candidates, gameDir.resolve(lvl));
            } catch (IOException e) {
                LOGGER.warn("[BetterEndFix] Could not read server.properties", e);
            }
        }

        // A “flat” level.dat in the gameDir
        checkAndAdd(candidates, gameDir);

        // All worlds under .minecraft/saves
        Path saves = gameDir.resolve("saves");
        if (Files.isDirectory(saves)) {
            try (Stream<Path> dirs = Files.list(saves)) {
                dirs.filter(Files::isDirectory)
                        .forEach(dir -> checkAndAdd(candidates, dir));
            } catch (IOException e) {
                LOGGER.error("[BetterEndFix] Error scanning saves folder", e);
            }
        }

        return candidates;
    }

    private void checkAndAdd(List<SaveWorldInfo> list, Path dir) {
        Path dat = dir.resolve("level.dat");
        File file = dat.toFile();
        if (file.exists() && levelNeedsFix(file)) {
            list.add(new SaveWorldInfo(dir.toFile(), file));
        }
    }


    private boolean levelNeedsFix(File levelDat) {
        try (InputStream is = Files.newInputStream(levelDat.toPath())) {
            NbtCompound root = NbtIo.readCompressed(is);

            NbtCompound worldGen = getWorldGenSettings(root);
            if (worldGen == null) {
                LOGGER.error("[BetterEndFix] No WorldGenSettings found in {}!", levelDat);
                return false;        // in levelNeedsFix
            }

            NbtCompound dims = worldGen.getCompound("dimensions");
            if (!dims.contains("minecraft:the_end")) {
                //LOGGER.info("[BetterEndFix] No End dimension found in {}, must recreate!", levelDat);
                return true;
            }

            NbtCompound endDim = dims.getCompound("minecraft:the_end");
            if (!endDim.contains("generator")) {
                //LOGGER.info("[BetterEndFix] No generator found in End dimension {}, must recreate!", levelDat);
                return true;
            }

            NbtCompound generator = endDim.getCompound("generator");
            return "bclib:betterx".equals(generator.getString("type"));
        } catch (IOException e) {
            LOGGER.warn("[BetterEndFix] Could not read {}", levelDat, e);
            return false;
        }
    }

    private void applyFix(SaveWorldInfo info) throws IOException {
        LOGGER.info("[BetterEndFix] Fixing world {}…", info.worldDir);

        NbtCompound root;
        try (InputStream is = Files.newInputStream(info.levelDat.toPath())) {
            root = NbtIo.readCompressed(is);
        }

        // backup level.dat
        Path backup = info.levelDat.toPath().resolveSibling("level.dat.betterendfixbackup");
        LOGGER.info("[BetterEndFix] Backing up level.dat to {}", backup);
        Files.copy(
                info.levelDat.toPath(),
                backup,
                StandardCopyOption.REPLACE_EXISTING
        );

        // locate and overwrite generator…
        NbtCompound worldGen = getWorldGenSettings(root);
        NbtCompound dims = worldGen.getCompound("dimensions");
        NbtCompound endDim = dims.getCompound("minecraft:the_end");
        // if endDim is an empty compound, rewrite
        if (endDim.isEmpty()) {
            LOGGER.info("[BetterEndFix] No End dimension settings found in {}, creating...", info.levelDat);
            endDim = new NbtCompound();
            endDim.putString("type", "minecraft:the_end");
            NbtCompound generator = new NbtCompound();
            endDim.put("generator", generator);
            dims.put("minecraft:the_end", endDim);
        }
        NbtCompound generator = endDim.getCompound("generator");

        // swap in vanilla end
        for (Iterator<String> it = generator.getKeys().iterator(); it.hasNext(); ) {
            it.next();
            it.remove();
        }
        generator.putString("type", "minecraft:noise");
        NbtCompound bs = new NbtCompound();
        bs.putString("type", "minecraft:the_end");
        generator.put("biome_source", bs);
        generator.putString("settings", "minecraft:end");

        // wipe DIM1
        Path dim1 = info.worldDir.toPath().resolve("DIM1");
        if (Files.exists(dim1)) {
            Path zip = info.worldDir.toPath().resolve("DIM1_betterendfixbackup.zip");
            LOGGER.info("[BetterEndFix] Backing up DIM1 to {}", zip);
            zipDirectory(dim1, zip);
            LOGGER.info("[BetterEndFix] Deleting DIM1 to force regen");
            deleteDirectory(dim1);
        }

        // write end island reset marker
        Path marker = info.worldDir.toPath().resolve(MARKER);
        Files.write(
                marker,
                new byte[0],
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        LOGGER.info("[BetterEndFix] Created end island reset marker at {}", marker);

        // write level.dat
        Path levelPath = info.levelDat.toPath();
        Path tmpPath = levelPath.resolveSibling("level.dat.betterendfixtmp");

        // write compressed NBT to the temp file
        try (OutputStream os = Files.newOutputStream(tmpPath)) {
            NbtIo.writeCompressed(root, os);
        }

        // atomically replace the original
        Files.move(
                tmpPath,
                levelPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
        LOGGER.info("[BetterEndFix] Wrote modified level.dat to {}", levelPath);
    }

    private void onWorldLoad(MinecraftServer server, ServerWorld world) {
        // Only run for the End
        if (!world.getRegistryKey().equals(World.END)) return;

        // Locate the save-folder & marker
        server.getSaveProperties().getLevelName();
        FabricLoader.getInstance().getGameDir();
        Path worldDir = server.getSavePath(WorldSavePath.ROOT);
        Path marker = worldDir.resolve(MARKER);
        if (!Files.exists(marker)) return;

        String saveName = server.getSaveProperties().getLevelName();
        LOGGER.info("[BetterEndFix] Detected end island reset marker for save '{}'", saveName);
        LOGGER.info("[BetterEndFix] Scheduling end island reset");

        // Schedule the command & marker deletion
        server.execute(() -> {
            ServerCommandSource source = server.getCommandSource();
            CommandDispatcher<ServerCommandSource> dispatcher =
                    server.getCommandManager().getDispatcher();

            try {
                dispatcher.execute(
                        dispatcher.parse("end_island reset", source)
                );
                LOGGER.info("[BetterEndFix] End Island reset command executed");
            } catch (CommandSyntaxException e) {
                LOGGER.error("[BetterEndFix] Failed to execute /end_island reset", e);
            }

            // Delete the marker so it only runs once
            try {
                if (Files.deleteIfExists(marker)) {
                    LOGGER.info("[BetterEndFix] Deleted marker {}", marker);
                }
            } catch (IOException ex) {
                LOGGER.warn("[BetterEndFix] Could not delete marker {}", marker, ex);
            }

        });
    }

    /* deleteDirectory via FileVisitor */
    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d,IOException exc) throws IOException {
                Files.delete(d); return FileVisitResult.CONTINUE;
            }
        });
    }

    /* zipDirectory via FileVisitor */
    private void zipDirectory(Path src, Path dst) throws IOException {
        try (ZipOutputStream zos=new ZipOutputStream(Files.newOutputStream(dst))) {
            Files.walkFileTree(src,new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a) throws IOException {
                    if (dir.equals(src)) return FileVisitResult.CONTINUE; // skip root entry
                    zos.putNextEntry(new ZipEntry(src.relativize(dir).toString()+"/"));
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f,BasicFileAttributes a) throws IOException {
                    ZipEntry e=new ZipEntry(src.relativize(f).toString());
                    zos.putNextEntry(e); Files.copy(f,zos); zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static @Nullable NbtCompound getWorldGenSettings(NbtCompound root) {
        if (!root.contains("Data", 10)) return null; // 10 = compound, https://minecraft.wiki/w/NBT_format
        NbtCompound data = root.getCompound("Data");
        return data.contains("WorldGenSettings", 10)
                ? data.getCompound("WorldGenSettings")
                : null;
    }


    private static class SaveWorldInfo {
        final File worldDir;
        final File levelDat;

        SaveWorldInfo(File wd, File ld) {
            worldDir = wd;
            levelDat = ld;
        }
    }
}