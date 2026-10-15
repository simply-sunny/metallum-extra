package com.metallumextra.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The color of block light at each point near the player.
 * <p>
 * The chunk mesh carries how bright block light is at a vertex but not what gave it, so a torch and a soul
 * lantern light their surroundings the same. This keeps a coarse grid of cells around the player (2 blocks a
 * cell, 96 blocks across and 64 high) and, for each, the color of the light-giving blocks within reach, weighted
 * by their brightness and how close they are. The shader tints block light by it. Brightness still comes from
 * the game's own light, so the grid does not need to know about walls: it only decides the color.
 * <p>
 * The grid is scanned a chunk column at a time across frames. Rebuilding is computed asynchronously on a
 * background worker so that large clusters of light sources (such as subterranean lava lakes and waterfalls)
 * never cause render-thread frame hitches.
 */
public final class LightColorGrid {
    public static final String SAMPLER = "MxLightColors";
    static final int CELL = 2;
    static final int WIDTH = 48;
    static final int HEIGHT = 32;
    static final int DEPTH = 48;
    private static final int COLUMNS = WIDTH * CELL / 16;
    private static final int TEXELS = WIDTH * HEIGHT * DEPTH;

    /** Light reaches this many cells per level of brightness. */
    private static final float REACH = 0.5F;

    private int originX, originY, originZ;
    private boolean placed;
    private int cursor;
    private int generation;
    private final IntArrayList[] columns = new IntArrayList[COLUMNS * COLUMNS];
    private final int[] columnCellLights = new int[8 * 8 * HEIGHT];

    private final Object uploadLock = new Object();
    private final ByteBuffer upload = ByteBuffer.allocateDirect(TEXELS * 4).order(ByteOrder.nativeOrder());
    private final ByteBuffer staging = ByteBuffer.allocateDirect(TEXELS * 4).order(ByteOrder.nativeOrder());
    private final float[] workerSum = new float[TEXELS * 4];

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MetallumExtra-LightGrid");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean updating;
    private volatile boolean ready;

    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;

    public LightColorGrid() {
        for (int i = 0; i < this.columns.length; i++) this.columns[i] = new IntArrayList();
    }

    /**
     * Makes sure the texture exists, outside any render pass: writing it ends the pass Metallum has open, and it
     * is bound from inside passes, so it cannot be made the first time it is asked for.
     */
    public void ensure() {
        if (this.view != null) return;
        this.texture = RenderSystem.getDevice().createTexture(() -> "Metallum Extra light colors",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, WIDTH * DEPTH, HEIGHT, 1, 1);
        this.view = RenderSystem.getDevice().createTextureView(this.texture);
        synchronized (this.uploadLock) {
            this.upload.clear();
            for (int i = 0; i < TEXELS; i++) this.upload.putInt(0);
            this.upload.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, this.upload, 0, 0, 0, 0, WIDTH * DEPTH, HEIGHT);
        }
    }

    /** Only after {@link #ensure()}. */
    public GpuTextureView view() {
        return this.view;
    }

    /** Once a frame, before the world is drawn. Writes the grid's placement into {@code globals}. */
    public void tick(final ClientLevel level, final Vec3 camera, final ShaderGlobals globals) {
        int wantedX = Math.floorDiv((int) Math.floor(camera.x) - WIDTH * CELL / 2, 16) * 16;
        int wantedY = Math.floorDiv((int) Math.floor(camera.y) - HEIGHT * CELL / 2, 16) * 16;
        int wantedZ = Math.floorDiv((int) Math.floor(camera.z) - DEPTH * CELL / 2, 16) * 16;
        // Only move the grid when the camera has come a whole chunk from where it was placed for.
        if (!this.placed || Math.abs(wantedX - this.originX) >= 32 || Math.abs(wantedY - this.originY) >= 32 || Math.abs(wantedZ - this.originZ) >= 32) {
            this.originX = wantedX;
            this.originY = wantedY;
            this.originZ = wantedZ;
            this.placed = true;
            this.cursor = 0;
            this.generation++;
            for (IntArrayList column : this.columns) column.clear();
        }
        globals.lightGrid.set((float) (this.originX - camera.x), (float) (this.originY - camera.y), (float) (this.originZ - camera.z), CELL);

        // Upload any finished background rebuild to the GPU texture without stalling the frame.
        if (this.ready) {
            synchronized (this.uploadLock) {
                this.ready = false;
                if (this.texture != null) {
                    RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, this.upload, 0, 0, 0, 0, WIDTH * DEPTH, HEIGHT);
                }
            }
        }

        this.scan(level, this.cursor);
        this.cursor++;
        if (this.cursor == this.columns.length) {
            this.cursor = 0;
            if (!this.updating) {
                this.updating = true;
                int currentGen = this.generation;
                int[][] snapshot = new int[this.columns.length][];
                for (int i = 0; i < this.columns.length; i++) {
                    snapshot[i] = this.columns[i].toIntArray();
                }
                this.worker.submit(() -> {
                    try {
                        this.rebuildAsync(snapshot, currentGen);
                    } finally {
                        this.updating = false;
                    }
                });
            }
        }
    }

    /** Lists the light-giving blocks of one chunk column of the grid. */
    private void scan(final ClientLevel level, final int index) {
        IntArrayList emitters = this.columns[index];
        emitters.clear();
        int chunkX = (this.originX >> 4) + index % COLUMNS;
        int chunkZ = (this.originZ >> 4) + index / COLUMNS;
        LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) return;
        int minSection = Math.max(level.getMinSectionY(), this.originY >> 4);
        int maxSection = Math.min(level.getMaxSectionY(), (this.originY + HEIGHT * CELL - 1) >> 4);

        Arrays.fill(this.columnCellLights, 0);

        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (section.hasOnlyAir()) continue;
            for (int y = 0; y < 16; y++) {
                int blockY = (sectionY << 4) + y;
                int cy = (blockY - this.originY) / CELL;
                if (cy < 0 || cy >= HEIGHT) continue;
                for (int z = 0; z < 16; z++) {
                    int cz = z / CELL;
                    for (int x = 0; x < 16; x++) {
                        var state = section.getBlockState(x, y, z);
                        int light = state.getLightEmission();
                        if (light <= 0) continue;

                        // Skip fluid blocks completely surrounded by the same fluid: interior fluid blocks emit no escape light.
                        if (state.getFluidState().is(FluidTags.LAVA)) {
                            if (x > 0 && x < 15 && y > 0 && y < 15 && z > 0 && z < 15
                                    && section.getBlockState(x - 1, y, z).getFluidState().is(FluidTags.LAVA)
                                    && section.getBlockState(x + 1, y, z).getFluidState().is(FluidTags.LAVA)
                                    && section.getBlockState(x, y - 1, z).getFluidState().is(FluidTags.LAVA)
                                    && section.getBlockState(x, y + 1, z).getFluidState().is(FluidTags.LAVA)
                                    && section.getBlockState(x, y, z - 1).getFluidState().is(FluidTags.LAVA)
                                    && section.getBlockState(x, y, z + 1).getFluidState().is(FluidTags.LAVA)) {
                                continue;
                            }
                        }

                        int color = BlockTypes.lightColor(state);
                        int cx = x / CELL;
                        int cellKey = (cy << 6) | (cz << 3) | cx;
                        int existing = this.columnCellLights[cellKey];
                        int currentPacked = (color << 4) | Math.min(light, 15);
                        // Within one 2x2x2 grid cell, one emitter of the same color and brightness is sufficient.
                        if (existing == currentPacked || ((existing >> 4) == color && (existing & 15) >= (currentPacked & 15))) {
                            continue;
                        }
                        this.columnCellLights[cellKey] = currentPacked;

                        int blockX = (chunkX << 4) + x, blockZ = (chunkZ << 4) + z;
                        emitters.add(((blockX - this.originX) << 20) | ((blockY - this.originY) << 12) | ((blockZ - this.originZ) << 4) | 0);
                        emitters.add(currentPacked);
                    }
                }
            }
        }
    }

    /** Adds every known light-giving block into the cells it reaches, running in the background. */
    private void rebuildAsync(final int[][] snapshot, final int gen) {
        float[] sum = this.workerSum;
        Arrays.fill(sum, 0.0F);
        for (int[] emitters : snapshot) {
            if (emitters == null) continue;
            for (int i = 0; i < emitters.length; i += 2) {
                int packed = emitters[i];
                int info = emitters[i + 1];
                float x = ((packed >> 20) & 0xFFF) / (float) CELL;
                float y = ((packed >> 12) & 0xFF) / (float) CELL;
                float z = ((packed >> 4) & 0xFF) / (float) CELL;
                int level = info & 15;
                int[] color = BlockTypes.LIGHT_COLORS[(info >> 4) & 7];
                float reach = Math.max(level * REACH, 1.0F);
                float reachSq = reach * reach;
                int span = (int) Math.ceil(reach);
                int cx = (int) x, cy = (int) y, cz = (int) z;
                for (int dz = -span; dz <= span; dz++) {
                    int tz = cz + dz;
                    if (tz < 0 || tz >= DEPTH) continue;
                    float fz = tz + 0.5F - z;
                    float fzSq = fz * fz;
                    if (fzSq >= reachSq) continue;
                    for (int dy = -span; dy <= span; dy++) {
                        int ty = cy + dy;
                        if (ty < 0 || ty >= HEIGHT) continue;
                        float fy = ty + 0.5F - y;
                        float fyzSq = fzSq + fy * fy;
                        if (fyzSq >= reachSq) continue;
                        for (int dx = -span; dx <= span; dx++) {
                            int tx = cx + dx;
                            if (tx < 0 || tx >= WIDTH) continue;
                            float fx = tx + 0.5F - x;
                            float distSq = fyzSq + fx * fx;
                            if (distSq >= reachSq) continue;
                            float distance = (float) Math.sqrt(distSq);
                            float falloff = 1.0F - distance / reach;
                            float weight = level * falloff * falloff / (distSq * 0.5F + 0.25F);
                            int at = ((ty * DEPTH + tz) * WIDTH + tx) * 4;
                            sum[at] += color[0] * weight;
                            sum[at + 1] += color[1] * weight;
                            sum[at + 2] += color[2] * weight;
                            sum[at + 3] += weight;
                        }
                    }
                }
            }
        }

        if (this.generation != gen) return;

        this.staging.clear();
        for (int i = 0; i < TEXELS; i++) {
            float weight = sum[i * 4 + 3];
            if (weight > 0.0F) {
                this.staging.put((byte) (int) (sum[i * 4] / weight))
                        .put((byte) (int) (sum[i * 4 + 1] / weight))
                        .put((byte) (int) (sum[i * 4 + 2] / weight));
                this.staging.put((byte) (int) Math.min(255.0F, weight * 40.0F));
            } else {
                this.staging.putInt(0);
            }
        }
        this.staging.flip();

        synchronized (this.uploadLock) {
            if (this.generation == gen) {
                this.upload.clear();
                this.upload.put(this.staging);
                this.upload.flip();
                this.ready = true;
            }
        }
    }

    public void close() {
        if (this.view != null) this.view.close();
        if (this.texture != null) this.texture.close();
        this.view = null;
        this.texture = null;
        this.placed = false;
        this.ready = false;
        this.updating = false;
        for (IntArrayList column : this.columns) column.clear();
    }
}
