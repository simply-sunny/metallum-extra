package com.metallumextra.voxy;

import com.metallumextra.MetallumExtra;
import com.metallumextra.mixin.voxy.ModelStoreBufferAccessor;
import com.metallumextra.voxy.gl.ModelAtlasMirror;
import com.metallumextra.voxy.gl.VoxyGlRegistry;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Stage 3: draws Voxy's LOD terrain into the game's main target through renderpearl, keeping every
 * CPU-side part of Voxy exactly as it is — the world ingest, the model bakery and the section
 * meshing service are constructed here (or reused when Voxy made them) and their results are simply
 * consumed by us instead of by Voxy's OpenGL node manager.
 *
 * <p>The frame is split the way Voxy splits it: {@link #tick} does the CPU work that Voxy spreads
 * over its render loop (mesh results, world changes, camera/uniform state, the LOD walk) and
 * {@link #render} opens one pass over the main target and issues one draw per visible section. The
 * quad vertices are Voxy's packed 8-byte quads expanded to a fixed 20-byte format by
 * {@link MetalVoxyVertexCapture}, so the vertex shader is a straight port of Voxy's quad decode.</p>
 *
 * <p>Fog, lighting, tinting and depth all come from vanilla: the camera render state carries the
 * fog values the game computed this frame, the lightmap is the game's own, and the world
 * projection is the game's with only the far plane pushed out (see {@link #WORLD_FAR}) so that
 * Voxy's far field is not clipped away.</p>
 */
public final class MetalVoxyRenderer {
    /** Sections land in one grow-only arena; retargeting it never invalidates in-flight draws (v1). */
    private static final long INITIAL_ARENA_BYTES = 64L << 20;
    /** {@code std140} mirror of the shaders' {@code SceneUniform} block. */
    private static final int UBO_BYTES = 144;
    /** One node's packed position, pushed as a {@code uvec2}. */
    private static final int PUSH_BYTES = 8;
    /** Physical near plane of the world projection ({@code Camera.update} → {@code setupPerspective}). */
    private static final float WORLD_NEAR = 0.05F;
    /**
     * Far plane of the world projection. Spec line 62 asks for {@code 1 << 22} rather than vanilla's
     * render-distance far: at vanilla's far plane Voxy's distant field would be clipped away, while
     * this value keeps every LOD level on screen (see the report for the resulting depth bias).
     */
    private static final float WORLD_FAR = 1 << 22;
    /** Requested nodes at this many queued meshes still ask for work, matching Voxy's own throttle. */
    private static final int REQUEST_QUEUE_TARGET = 4000;

    /** A node has no mesh yet (never asked), a task in flight, geometry, or nothing at all. */
    private static final byte ST_UNKNOWN = 0;
    private static final byte ST_PENDING = 1;
    private static final byte ST_BUILT = 2;
    /** The section exists but is all air: draw nothing, and do not ask for it again. */
    private static final byte ST_EMPTY = 3;
    /** The section is missing from storage: the nearest built ancestor stands in for it. */
    private static final byte ST_NO_DATA = 4;

    /** What the world-change callback reports; it may arrive on any thread, so it is queued. */
    private record WorldChange(long key, int flags, int neighbours) {
    }

    /** One bind group for the whole pass: scene uniforms, the model atlas and the lightmap. */
    private static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withUniform("SceneUniform", UniformType.UNIFORM_BUFFER)
            .withUniform("u_Atlas", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("u_Lightmap", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    /**
     * Voxy packs a quad into two 32-bit words and four extra words, so the Metal format keeps the
     * quad pair as one RG32_UINT element (the same shape Sodium uses for {@code a_Position}) and
     * the rest as three R32_UINT elements — 20 bytes, all of it straight from the mesh buffer.
     */
    private static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
            .addAttribute("a_Quad", GpuFormat.RG32_UINT)
            .addAttribute("a_FaceData", GpuFormat.R32_UINT)
            .addAttribute("a_ModelFlags", GpuFormat.R32_UINT)
            .addAttribute("a_Tint", GpuFormat.R32_UINT)
            .build();

    private final Level level;
    private final WorldEngine world;
    private final ModelBakerySubsystem bakery;
    private final RenderGenerationService generation;
    private final RenderDistanceTracker tracker;

    /** Stable ids of the two model-store buffers the capture reads; resolved to addresses per section. */
    private final int modelBufferId;
    private final int colourBufferId;

    // ---- node bookkeeping ----
    private final Long2ByteOpenHashMap nodeState = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap childMask = new Long2ByteOpenHashMap();
    private final Long2LongOpenHashMap meshOffset = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap meshCount = new Long2IntOpenHashMap();
    private final LongOpenHashSet topNodes = new LongOpenHashSet();
    /** Nodes whose section changed under us; re-meshed the next time they are selected. */
    private final LongOpenHashSet staleNodes = new LongOpenHashSet();
    /** Sections whose data never arrived; their nearest built ancestor is drawn instead. */
    private final LongOpenHashSet fallbackRoots = new LongOpenHashSet();

    private final LongArrayList visible = new LongArrayList();
    private final LongArrayList noData = new LongArrayList();
    private final LongArrayList draws = new LongArrayList();

    // ---- results handed over by the meshing workers ----
    private final ConcurrentLinkedQueue<MetalVoxyVertexCapture.Result> results = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<WorldChange> changes = new ConcurrentLinkedQueue<>();

    // ---- camera state, kept between tick and render ----
    private final Matrix4f projection = new Matrix4f();
    /** Camera-relative projection-view (rotation only, no translation) for culling and screen size. */
    private final Matrix4f projectionView = new Matrix4f();
    /** The same, with the sub-section camera translation folded in, so the shader works in section units. */
    private final Matrix4f mvp = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Vector4f clipScratch = new Vector4f();
    private final int[] baseSectionPos = new int[3];
    private final float[] cameraSubPos = new float[3];
    private float camX, camY, camZ;
    private float renderDistanceSquared;
    private float minScreenArea;
    private float vanillaRenderDistance;
    private int trackerRenderDistance;
    private FogData cameraFog;
    private final ByteBuffer ubo = ByteBuffer.allocateDirect(UBO_BYTES).order(ByteOrder.nativeOrder());
    private final ByteBuffer push = ByteBuffer.allocateDirect(PUSH_BYTES).order(ByteOrder.nativeOrder());
    private final float[] screenX = new float[8];
    private final float[] screenY = new float[8];

    // ---- GPU resources, created lazily on the first draw ----
    private RenderPipeline pipeline;
    private GpuBuffer arena;
    private long arenaCapacity;
    private long arenaUsed;
    /** Freed arena ranges, sorted and coalesced, so re-meshing does not grow the arena forever. */
    private final ArrayList<long[]> freeHoles = new ArrayList<>();
    /** Arenas that were replaced by a bigger one; kept alive because draws may still reference them. */
    private final ArrayList<GpuBuffer> retiredArenas = new ArrayList<>();
    private GpuBuffer sceneUniform;
    private GpuTexture atlasTexture;
    private GpuTextureView atlasView;
    private GpuSampler atlasSampler;
    private GpuSampler lightmapSampler;

    private int frameId;
    private int requestBudget;
    private boolean warnedPipeline;
    private boolean closed;

    public MetalVoxyRenderer(final Level level) {
        this.level = level;
        final VoxyInstance instance = me.cortex.voxy.commonImpl.VoxyCommon.getInstance();
        final WorldEngine engine = WorldIdentifier.of(level).getOrCreateEngine(true);
        engine.acquireRef();
        this.world = engine;
        ModelBakerySubsystem newBakery = null;
        RenderGenerationService newGeneration = null;
        try {
            final ServiceManager services = instance.getServiceManager();
            newBakery = new ModelBakerySubsystem(engine.getMapper());
            newGeneration = new RenderGenerationService(engine, newBakery, services, false);
            newGeneration.setResultConsumer(this::onBuiltSection);
            for (final Mapper.BiomeEntry biome : engine.getMapper().getBiomeEntries()) {
                newBakery.addBiome(biome);
            }
            engine.getMapper().setBiomeCallback(newBakery::addBiome);
            // Voxy's own path is WorldEngine.setDirtyCallback -> nodeManager.worldEvent -> the update
            // router; we take the same callback and fold it into the node table directly.
            engine.setDirtyCallback(this::onWorldDirty);

            if (!(newBakery.getStore() instanceof ModelStoreBufferAccessor accessor)) {
                throw new IllegalStateException("ModelStoreBufferAccessor not registered: the Voxy "
                        + "model store exposes no buffer ids, so section capture cannot run");
            }
            this.modelBufferId = accessor.metallumExtra$modelBuffer().id;
            this.colourBufferId = accessor.metallumExtra$modelColourBuffer().id;

            this.bakery = newBakery;
            this.generation = newGeneration;
            this.trackerRenderDistance = (int) Math.ceil(VoxyConfig.CONFIG.sectionRenderDistance + 1);
            this.tracker = new RenderDistanceTracker(40,
                    level.getMinSectionY() >> 5, (level.getMaxSectionY() - 1) >> 5,
                    this::onTopLevelAdded, this::onTopLevelRemoved);
            this.tracker.setRenderDistance(this.trackerRenderDistance);
            this.nodeState.defaultReturnValue(ST_UNKNOWN);
            this.childMask.defaultReturnValue((byte) -1);
            this.meshOffset.defaultReturnValue(-1L);
            this.meshCount.defaultReturnValue(-1);
            MetallumExtra.LOGGER.info("[Metallum Extra] Creating Voxy render system for {}", level.dimension().identifier());
        } catch (final RuntimeException | Error failure) {
            if (newGeneration != null) {
                newGeneration.shutdown();
            }
            if (newBakery != null) {
                newBakery.shutdown();
            }
            this.world.releaseRef();
            throw failure;
        }
    }

    public Level level() {
        return this.level;
    }

    /** Frees everything Voxy lent us. Safe to call twice; the callbacks are dropped first. */
    public void shutdown() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.world.setDirtyCallback(null);
        this.world.getMapper().setBiomeCallback(null);
        this.generation.shutdown();
        this.bakery.shutdown();
        MetalVoxyVertexCapture.Result result;
        while ((result = this.results.poll()) != null) {
            MetalVoxyVertexCapture.free(result);
        }
        this.changes.clear();
        if (this.sceneUniform != null) {
            this.sceneUniform.close();
            this.sceneUniform = null;
        }
        if (this.atlasView != null) {
            this.atlasView.close();
            this.atlasView = null;
        }
        if (this.atlasTexture != null) {
            this.atlasTexture.close();
            this.atlasTexture = null;
        }
        if (this.arena != null) {
            this.arena.close();
            this.arena = null;
        }
        for (final GpuBuffer retired : this.retiredArenas) {
            retired.close();
        }
        this.retiredArenas.clear();
        ModelAtlasMirror.reset();
        this.nodeState.clear();
        this.childMask.clear();
        this.meshOffset.clear();
        this.meshCount.clear();
        this.topNodes.clear();
        this.staleNodes.clear();
        this.world.releaseRef();
    }

    // ------------------------------------------------------------------ frame

    /**
     * Everything that has to happen before the pass: mesh results are uploaded, world changes are
     * folded in, camera-derived uniforms are written and the LOD walk picks the sections to draw.
     */
    public void tick(final CameraRenderState camera) {
        if (this.closed) {
            return;
        }
        this.bakery.tick(900_000L);
        this.drainResults();
        this.drainWorldChanges();
        this.updateCamera(camera);
        this.flushAtlas();
        this.tracker.setCenterAndProcess(camera.pos.x, camera.pos.z);
        this.writeUniforms();
        this.walk();
    }

    /** Draws the sections the walk selected, right into the main target, on top of the world. */
    public void render(final CameraRenderState camera) {
        if (this.closed) {
            return;
        }
        this.ensureResources();
        if (this.pipeline == null || this.sceneUniform == null || this.atlasView == null) {
            return;
        }
        final GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.levelLightmap();
        if (lightmap == null) {
            return;
        }
        // Compiled per frame through the cache: the game closes that cache on shader reloads, so a
        // cached pointer of ours would dangle. A compile failure just skips the frame.
        final CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(this.pipeline);
        if (compiled == null) {
            if (!this.warnedPipeline) {
                this.warnedPipeline = true;
                MetallumExtra.LOGGER.warn("[Metallum Extra] Voxy terrain pipeline failed to compile, skipping");
            }
            return;
        }
        final RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (this.draws.isEmpty()) {
            return;
        }
        final RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Metallum Extra voxy terrain")
                .withColorAttachment(main.getColorTextureView())
                .withDepthAttachment(main.getDepthTextureView())
                .build();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
            pass.setPipeline(compiled);
            pass.setUniform("SceneUniform", this.sceneUniform);
            pass.setUniform("u_Atlas", this.atlasView, this.atlasSampler);
            pass.setUniform("u_Lightmap", lightmap, this.lightmapSampler);
            for (int i = 0; i < this.draws.size(); i++) {
                final long pos = this.draws.getLong(i);
                final long offset = this.meshOffset.get(pos);
                final int count = this.meshCount.get(pos);
                if (offset < 0L || count <= 0) {
                    continue;
                }
                // The shader reads a uvec2 whose first word is the high half, so level is in .x.
                this.push.putInt(0, (int) (pos >>> 32)).putInt(4, (int) pos).position(0).limit(PUSH_BYTES);
                pass.pushConstants(this.push);
                pass.setVertexBuffer(0, this.arena.slice(offset, (long) count * MetalVoxyVertexCapture.VERTEX_STRIDE));
                pass.draw(count, 1, 0, 0);
            }
        }
    }

    // ------------------------------------------------------------------ inputs

    /** Voxy's meshing service calls this on a worker thread; we only copy and queue. */
    private void onBuiltSection(final BuiltSection section) {
        try {
            final long modelBase = VoxyGlRegistry.address(this.modelBufferId);
            final long colourBase = VoxyGlRegistry.address(this.colourBufferId);
            final MetalVoxyVertexCapture.Result result;
            if (modelBase == 0L || colourBase == 0L) {
                result = new MetalVoxyVertexCapture.Result(section.position, section.childExistence, 0L, 0);
            } else {
                result = new MetalVoxyVertexCapture(modelBase, colourBase, VoxyGlRegistry.size(this.colourBufferId))
                        .capture(section);
            }
            this.results.add(result);
        } finally {
            section.free();
        }
    }

    /** Runs on whatever thread touched the section; queued and applied on the render thread. */
    private void onWorldDirty(final WorldSection section, final int updateFlags, final int neighbourMask) {
        this.changes.add(new WorldChange(section.key, updateFlags, neighbourMask));
    }

    private void onTopLevelAdded(final long pos) {
        this.topNodes.add(pos);
    }

    private void onTopLevelRemoved(final long pos) {
        this.topNodes.remove(pos);
    }

    // ------------------------------------------------------------------ results

    private void drainResults() {
        final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        MetalVoxyVertexCapture.Result result;
        while ((result = this.results.poll()) != null) {
            final long pos = result.position();
            this.childMask.put(pos, result.childExistence());
            if (result.hasGeometry()) {
                final long bytes = (long) result.vertexCount() * MetalVoxyVertexCapture.VERTEX_STRIDE;
                final long offset = this.allocate(bytes);
                encoder.writeToBuffer(this.arena.slice(offset, bytes),
                        MemoryUtil.memByteBuffer(result.vertexAddress(), (int) bytes));
                final long previous = this.meshOffset.put(pos, offset);
                final int previousCount = this.meshCount.put(pos, result.vertexCount());
                if (previous >= 0L && previousCount > 0) {
                    this.freeRange(previous, (long) previousCount * MetalVoxyVertexCapture.VERTEX_STRIDE);
                }
                this.nodeState.put(pos, ST_BUILT);
                MetalVoxyVertexCapture.free(result);
            } else {
                // Empty sections come in two shapes: an existing all-air section (draw nothing, and
                // never ask again) and a section that storage simply does not have, which is what
                // "BuiltSection.empty" means — for that one the nearest built ancestor stands in.
                this.dropMesh(pos);
                this.nodeState.put(pos, this.sectionExists(pos) ? ST_EMPTY : ST_NO_DATA);
            }
        }
    }

    private boolean sectionExists(final long pos) {
        final WorldSection section = this.world.acquireIfExists(pos);
        if (section == null) {
            return false;
        }
        section.release();
        return true;
    }

    private void dropMesh(final long pos) {
        final long offset = this.meshOffset.remove(pos);
        final int count = this.meshCount.remove(pos);
        if (offset >= 0L && count > 0) {
            this.freeRange(offset, (long) count * MetalVoxyVertexCapture.VERTEX_STRIDE);
        }
    }

    private void drainWorldChanges() {
        WorldChange change;
        while ((change = this.changes.poll()) != null) {
            final int level = WorldEngine.getLevel(change.key());
            final int x = WorldEngine.getX(change.key());
            final int y = WorldEngine.getY(change.key());
            final int z = WorldEngine.getZ(change.key());
            if ((change.flags() & WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT) != 0) {
                final WorldSection section = this.world.acquireIfExists(change.key());
                if (section != null) {
                    this.childMask.put(change.key(), section.getNonEmptyChildren());
                    section.release();
                }
            }
            if ((change.flags() & WorldEngine.UPDATE_TYPE_BLOCK_BIT) != 0) {
                // The section itself and, because a face can start or stop at its border, every
                // neighbour the update reports. The old mesh keeps drawing until the new one lands.
                final int mask = change.neighbours();
                this.staleNodes.add(change.key());
                if ((mask & (1 << 0)) != 0) {
                    this.staleNodes.add(WorldEngine.getWorldSectionId(level, x, y - 1, z));
                }
                if ((mask & (1 << 1)) != 0) {
                    this.staleNodes.add(WorldEngine.getWorldSectionId(level, x, y + 1, z));
                }
                if ((mask & (1 << 2)) != 0) {
                    this.staleNodes.add(WorldEngine.getWorldSectionId(level, x - 1, y, z));
                }
                if ((mask & (1 << 3)) != 0) {
                    this.staleNodes.add(WorldEngine.getWorldSectionId(level, x + 1, y, z));
                }
                if ((mask & (1 << 4)) != 0) {
                    this.staleNodes.add(WorldEngine.getWorldSectionId(level, x, y, z - 1));
                }
                if ((mask & (1 << 5)) != 0) {
                    this.staleNodes.add(WorldEngine.getWorldSectionId(level, x, y, z + 1));
                }
            }
        }
    }

    // ------------------------------------------------------------------ camera

    private void updateCamera(final CameraRenderState camera) {
        final Vec3 pos = camera.pos;
        this.camX = (float) pos.x;
        this.camY = (float) pos.y;
        this.camZ = (float) pos.z;
        final int sectionX = Mth.floor(pos.x) >> 5;
        final int sectionY = Mth.floor(pos.y) >> 5;
        final int sectionZ = Mth.floor(pos.z) >> 5;
        this.baseSectionPos[0] = sectionX;
        this.baseSectionPos[1] = sectionY;
        this.baseSectionPos[2] = sectionZ;
        this.cameraSubPos[0] = (float) (pos.x - sectionX * 32.0);
        this.cameraSubPos[1] = (float) (pos.y - sectionY * 32.0);
        this.cameraSubPos[2] = (float) (pos.z - sectionZ * 32.0);
        this.cameraFog = camera.fogData;
        this.vanillaRenderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F;

        // Same matrix the game renders the world with, with only the two depth terms replaced by
        // the perspective pair for near 0.05 / far 1<<22 (reverse zero-to-one, as the game is).
        this.projection.set(camera.projectionMatrix)
                .m22(WORLD_NEAR / (WORLD_FAR - WORLD_NEAR))
                .m32(WORLD_FAR * WORLD_NEAR / (WORLD_FAR - WORLD_NEAR));
        this.projectionView.set(this.projection).mul(camera.viewRotationMatrix);
        this.mvp.set(this.projectionView)
                .translate(-this.cameraSubPos[0], -this.cameraSubPos[1], -this.cameraSubPos[2]);
        // Culling and screen size stay in the untranslated frame, with camera-relative bounds: that
        // is how Voxy's screenspace code tests nodes, and it keeps the frustum free of the offset.
        this.frustum.set(this.projectionView, false);

        final float sectionRenderDistance = VoxyConfig.CONFIG.sectionRenderDistance;
        final int trackerRenderDistance = (int) Math.ceil(sectionRenderDistance + 1);
        if (trackerRenderDistance != this.trackerRenderDistance) {
            this.trackerRenderDistance = trackerRenderDistance;
            this.tracker.setRenderDistance(trackerRenderDistance);
        }
        final RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        final int width = Math.max(1, main.getColorTextureView().getWidth(0));
        final int height = Math.max(1, main.getColorTextureView().getHeight(0));
        final float subdivision = VoxyConfig.CONFIG.subDivisionSize;
        this.minScreenArea = subdivision * subdivision / (float) ((long) width * height);
        final float renderDistance = VoxyConfig.CONFIG.sectionRenderDistance * 16.0F * 32.0F;
        this.renderDistanceSquared = renderDistance * renderDistance;
    }

    /** Writes the {@code SceneUniform} block the two shaders share. */
    private void writeUniforms() {
        // Fog comes from the values the game computed for this frame: environmental start/end (the
        // overworld pair is 0..1024), the render distance the player chose, and the fog colour.
        final FogData cameraFog = this.cameraFog;
        final float vanillaRenderDistance = this.vanillaRenderDistance;
        float fogStart = 0.0F;
        float fogEnd = 0.0F;
        float clampMax = 0.0F;
        float fogRed = 0.0F;
        float fogGreen = 0.0F;
        float fogBlue = 0.0F;
        if (cameraFog != null && Math.abs(cameraFog.environmentalEnd - cameraFog.environmentalStart) > 1.0F) {
            fogStart = cameraFog.environmentalStart;
            fogEnd = cameraFog.environmentalEnd;
            final float inv = 1.0F / (fogEnd - fogStart);
            final float endDistance = Math.max(vanillaRenderDistance, 20.0F * 16.0F) * (float) Math.sqrt(3.0);
            // Voxy fades its field out rather than letting distant terrain reach full fog, which is
            // what gives it its signature haze; the cap is that fade's starting value.
            clampMax = Math.clamp(endDistance * inv + (-fogStart * inv), 0.0F, 1.0F);
            fogRed = cameraFog.color.x;
            fogGreen = cameraFog.color.y;
            fogBlue = cameraFog.color.z;
        }
        final float sectionRenderDistance = VoxyConfig.CONFIG.sectionRenderDistance;
        final float fadeEnd = Math.max(vanillaRenderDistance, sectionRenderDistance * 16.0F * 32.0F - 32.0F);
        final float fadeStart = Math.max(vanillaRenderDistance, (sectionRenderDistance * 16.0F * 32.0F - 32.0F) * 0.9F);

        this.ubo.position(0);
        this.mvp.get(0, this.ubo);
        this.ubo.putInt(64, this.baseSectionPos[0]).putInt(68, this.baseSectionPos[1]).putInt(72, this.baseSectionPos[2]);
        this.ubo.putInt(76, this.frameId);
        this.ubo.putFloat(80, this.cameraSubPos[0]).putFloat(84, this.cameraSubPos[1]).putFloat(88, this.cameraSubPos[2]);
        this.ubo.putFloat(96, fogStart).putFloat(100, fogEnd).putFloat(104, fadeStart).putFloat(108, fadeEnd);
        // Vanilla's fog colour is always opaque, so its alpha channel carries the fog cap instead.
        this.ubo.putFloat(112, fogRed).putFloat(116, fogGreen).putFloat(120, fogBlue).putFloat(124, clampMax);
        if (this.level instanceof ClientLevel clientLevel) {
            final CardinalLighting tints = clientLevel.cardinalLighting();
            this.ubo.putFloat(128, tints.down()).putFloat(132, tints.up())
                    .putFloat(136, tints.north()).putFloat(140, tints.east());
        }
        this.ubo.position(0).limit(UBO_BYTES);
        this.frameId++;
    }

    // ------------------------------------------------------------------ the walk

    private void walk() {
        this.visible.clear();
        this.noData.clear();
        this.draws.clear();
        this.fallbackRoots.clear();
        this.requestBudget = (int) Math.ceil(Math.pow(
                Math.max(0.0F, (REQUEST_QUEUE_TARGET - this.generation.getTaskCount()) / (float) REQUEST_QUEUE_TARGET), 2) * 50);
        final LongIterator nodes = this.topNodes.iterator();
        while (nodes.hasNext()) {
            final long pos = nodes.nextLong();
            this.visit(pos, WorldEngine.getLevel(pos));
        }
        // A section whose data never arrived is covered by the nearest ancestor that does have a
        // mesh; anything else inside that ancestor would overlap it, so it is skipped below.
        for (int i = 0; i < this.noData.size(); i++) {
            final long root = this.fallbackRoot(this.noData.getLong(i));
            if (root != -1L) {
                this.fallbackRoots.add(root);
            }
        }
        for (int i = 0; i < this.visible.size(); i++) {
            final long pos = this.visible.getLong(i);
            if (!this.isShadowed(pos)) {
                this.draws.add(pos);
            }
        }
        final LongIterator roots = this.fallbackRoots.iterator();
        while (roots.hasNext()) {
            final long root = roots.nextLong();
            if (!this.isShadowed(root) && !this.draws.contains(root)) {
                this.draws.add(root);
            }
        }
    }

    private void visit(final long pos, final int level) {
        final int x = WorldEngine.getX(pos);
        final int y = WorldEngine.getY(pos);
        final int z = WorldEngine.getZ(pos);
        final float size = (float) (32 << level);
        final float originX = x * size;
        final float originY = y * size;
        final float originZ = z * size;

        final float closestX = closestOnAxis(originX, this.camX, size);
        final float closestZ = closestOnAxis(originZ, this.camZ, size);
        if (closestX * closestX + closestZ * closestZ > this.renderDistanceSquared) {
            return;
        }

        final float minX = originX - this.camX;
        final float minY = originY - this.camY;
        final float minZ = originZ - this.camZ;
        if (!this.frustum.testAab(minX, minY, minZ, minX + size, minY + size, minZ + size)) {
            return;
        }

        final byte state = this.nodeState.get(pos);
        final byte mask = this.childMask.get(pos);
        if (level != 0 && this.projectedArea(minX, minY, minZ, size) > this.minScreenArea) {
            if (mask > 0) {
                this.visitChildren(pos, level, x, y, z, mask);
            } else {
                this.maybeRequest(pos, state);
                if (state == ST_BUILT) {
                    this.visible.add(pos);
                }
            }
            return;
        }
        if (state == ST_BUILT) {
            this.visible.add(pos);
            return;
        }
        if (state == ST_NO_DATA) {
            // A section that has since arrived in storage is marked stale by the world callback, so
            // asking here is what turns a fallback ancestor into real geometry.
            this.maybeRequest(pos, state);
            this.noData.add(pos);
            return;
        }
        this.maybeRequest(pos, state);
        // Voxy descends into a node without a mesh when it does have children: their meshes are the
        // only thing that can fill it in, and this is what makes the field refine as you approach.
        if (mask > 0) {
            this.visitChildren(pos, level, x, y, z, mask);
        }
    }

    private void visitChildren(final long pos, final int level, final int x, final int y, final int z, final byte mask) {
        if (level <= 0) {
            return;
        }
        final int childLevel = level - 1;
        final int baseX = x << 1;
        final int baseY = y << 1;
        final int baseZ = z << 1;
        for (int bit = 0; bit < 8; bit++) {
            if ((mask & (1 << bit)) == 0) {
                continue;
            }
            final int dx = bit & 1;
            final int dz = (bit >> 1) & 1;
            final int dy = (bit >> 2) & 1;
            this.visit(WorldEngine.getWorldSectionId(childLevel, baseX + dx, baseY + dy, baseZ + dz), childLevel);
        }
    }

    /** Asks for a node's mesh, once, and never for one that is already in flight. */
    private void maybeRequest(final long pos, final byte state) {
        final boolean stale = this.staleNodes.remove(pos);
        if (!stale && state != ST_UNKNOWN) {
            return;
        }
        if (this.requestBudget <= 0) {
            if (stale) {
                this.staleNodes.add(pos);
            }
            return;
        }
        this.requestBudget--;
        if (state == ST_UNKNOWN) {
            this.nodeState.put(pos, ST_PENDING);
        }
        this.generation.enqueueTask(pos);
    }

    private long fallbackRoot(final long pos) {
        final int level = WorldEngine.getLevel(pos);
        final int x = WorldEngine.getX(pos);
        final int y = WorldEngine.getY(pos);
        final int z = WorldEngine.getZ(pos);
        for (int up = level + 1; up <= 4; up++) {
            final int shift = up - level;
            final long ancestor = WorldEngine.getWorldSectionId(up, x >> shift, y >> shift, z >> shift);
            if (this.nodeState.get(ancestor) == ST_BUILT) {
                return ancestor;
            }
        }
        return -1L;
    }

    private boolean isShadowed(final long pos) {
        final int level = WorldEngine.getLevel(pos);
        final int x = WorldEngine.getX(pos);
        final int y = WorldEngine.getY(pos);
        final int z = WorldEngine.getZ(pos);
        for (int up = level + 1; up <= 4; up++) {
            final int shift = up - level;
            if (this.fallbackRoots.contains(WorldEngine.getWorldSectionId(up, x >> shift, y >> shift, z >> shift))) {
                return true;
            }
        }
        return false;
    }

    private static float closestOnAxis(final float origin, final float camera, final float size) {
        if (camera < origin) {
            return origin - camera;
        }
        return camera > origin + size ? camera - (origin + size) : 0.0F;
    }

    /**
     * The projected size of the node's box as a fraction of the screen, the way Voxy's
     * {@code screenspace.glsl} measures it: project the eight corners, map NDC into [0,1] and add
     * the two triangle fans that cover the box. Corners that sit on the camera plane are treated as
     * filling the screen, which sends the walk down into the node — the right answer for a section
     * the camera is standing in.
     */
    private float projectedArea(final float minX, final float minY, final float minZ, final float size) {
        for (int i = 0; i < 8; i++) {
            final float x = minX + ((i & 1) != 0 ? size : 0.0F);
            final float y = minY + ((i & 2) != 0 ? size : 0.0F);
            final float z = minZ + ((i & 4) != 0 ? size : 0.0F);
            this.projectionView.transform(x, y, z, 1.0F, this.clipScratch);
            final float w = this.clipScratch.w;
            if (Math.abs(w) < 1.0E-6F) {
                return Float.POSITIVE_INFINITY;
            }
            this.screenX[i] = this.clipScratch.x / w * 0.5F + 0.5F;
            this.screenY[i] = this.clipScratch.y / w * 0.5F + 0.5F;
        }
        float area = 0.0F;
        area += this.cornerArea(0, 1, 3) + this.cornerArea(0, 3, 2) + this.cornerArea(0, 2, 6)
                + this.cornerArea(0, 6, 4) + this.cornerArea(0, 4, 5) + this.cornerArea(0, 5, 1);
        area += this.cornerArea(7, 6, 4) + this.cornerArea(7, 4, 5) + this.cornerArea(7, 5, 1)
                + this.cornerArea(7, 1, 3) + this.cornerArea(7, 3, 2) + this.cornerArea(7, 2, 6);
        return area * 0.5F;
    }

    private float cornerArea(final int a, final int b, final int c) {
        final float ux = this.screenX[b] - this.screenX[a];
        final float uy = this.screenY[b] - this.screenY[a];
        final float vx = this.screenX[c] - this.screenX[a];
        final float vy = this.screenY[c] - this.screenY[a];
        return Math.abs(ux * vy - uy * vx);
    }

    // ------------------------------------------------------------------ resources

    private void flushAtlas() {
        if (this.atlasTexture != null && ModelAtlasMirror.hasDirty()) {
            ModelAtlasMirror.flush(this.atlasTexture);
        }
    }

    private void ensureResources() {
        if (this.pipeline == null) {
            final Identifier location = Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "pipeline/voxy_terrain");
            this.pipeline = RenderPipeline.builder()
                    .withLocation(location)
                    .withVertexShader(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "voxy/terrain"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "voxy/terrain"))
                    .withVertexBinding(0, VERTEX_FORMAT)
                    .withBindGroupLayout(BIND_GROUP)
                    .withPushConstantSize(PUSH_BYTES)
                    // Voxy draws its opaque terrain double-sided, and the terrain is watertight, so
                    // back-face culling would only ever remove quads seen from behind.
                    .withCull(false)
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .build();
        }
        if (this.sceneUniform == null) {
            this.sceneUniform = RenderSystem.getDevice().createBuffer(() -> "Voxy scene uniforms",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_BYTES);
        }
        if (this.arena == null) {
            this.arena = RenderSystem.getDevice().createBuffer(() -> "Voxy vertex arena",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, INITIAL_ARENA_BYTES);
            this.arenaCapacity = INITIAL_ARENA_BYTES;
        }
        if (this.atlasTexture == null) {
            this.atlasTexture = RenderSystem.getDevice().createTexture(() -> "Voxy model atlas",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM,
                    ModelAtlasMirror.ATLAS_W, ModelAtlasMirror.ATLAS_H, 1, 1);
            this.atlasView = RenderSystem.getDevice().createTextureView(this.atlasTexture);
            if (ModelAtlasMirror.hasDirty()) {
                ModelAtlasMirror.flush(this.atlasTexture);
            }
        }
        if (this.atlasSampler == null) {
            // The mirror is mip-less and Voxy magnifies block textures nearest-filtered, so the
            // atlas samples the same way; the lightmap stays linear, as Voxy's own sampler is.
            this.atlasSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, false);
            this.lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, false);
        }
    }

    /** First fit over the freed holes, then the end of the arena; growth copies the live prefix. */
    private long allocate(final long bytes) {
        for (int i = 0; i < this.freeHoles.size(); i++) {
            final long[] hole = this.freeHoles.get(i);
            if (hole[1] >= bytes) {
                final long offset = hole[0];
                if (hole[1] == bytes) {
                    this.freeHoles.remove(i);
                } else {
                    hole[0] = offset + bytes;
                    hole[1] -= bytes;
                }
                return offset;
            }
        }
        this.ensureCapacity(this.arenaUsed + bytes);
        final long offset = this.arenaUsed;
        this.arenaUsed += bytes;
        return offset;
    }

    private void ensureCapacity(final long needed) {
        if (needed <= this.arenaCapacity) {
            return;
        }
        final long capacity = Math.max(this.arenaCapacity * 2L, needed);
        final GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> "Voxy vertex arena",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacity);
        if (this.arenaUsed > 0L) {
            RenderSystem.getDevice().createCommandEncoder()
                    .copyToBuffer(this.arena.slice(0, this.arenaUsed), next.slice(0, this.arenaUsed));
        }
        if (this.arena != null) {
            // Draws from this frame may still reference the old arena, so it is closed at shutdown.
            this.retiredArenas.add(this.arena);
        }
        this.arena = next;
        this.arenaCapacity = capacity;
    }

    /** Returns a range to the hole list, merging it with the ranges on either side. */
    private void freeRange(final long offset, final long size) {
        if (size <= 0L) {
            return;
        }
        int index = 0;
        while (index < this.freeHoles.size() && this.freeHoles.get(index)[0] + this.freeHoles.get(index)[1] <= offset) {
            index++;
        }
        long start = offset;
        long end = offset + size;
        if (index < this.freeHoles.size() && this.freeHoles.get(index)[0] == end) {
            final long[] after = this.freeHoles.remove(index);
            end += after[1];
        }
        if (index > 0 && this.freeHoles.get(index - 1)[0] + this.freeHoles.get(index - 1)[1] == start) {
            start = this.freeHoles.remove(index - 1)[0];
            index--;
        }
        this.freeHoles.add(index, new long[]{start, end - start});
    }
}
