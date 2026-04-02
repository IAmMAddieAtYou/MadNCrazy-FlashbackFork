package com.moulberry.flashback.exporting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.moulberry.flashback.FlashbackSystemToasts;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.moulberry.flashback.exporting.depthsettings.DEPTHEXPORT;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeFov;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.KeyframeTrack;
import com.moulberry.flashback.state.RealTimeMapping;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.util.Mth;
import org.lwjgl.opengl.GL11;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FreezeSlowdownFormula;
import com.moulberry.flashback.Utils;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.exporting.taskbar.TaskbarManager;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import com.moulberry.flashback.keyframe.handler.MinecraftKeyframeHandler;
import com.moulberry.flashback.keyframe.handler.TickrateKeyframeCapture;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.visuals.AccurateEntityPositionHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.bytedeco.ffmpeg.global.avutil;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class ExportJob {

    private final ExportSettings settings;
    private boolean running = false;
    private boolean shouldChangeFramebufferSize = false;
    private long lastRenderMillis;
    private long escapeCancelStartMillis = -1;
    private long renderStartTime;

    private final Random particleRandom;

    private  static RenderTarget exportrenderTarget;
    private boolean showingDebug = false;
    private boolean pressedDebugKey = false;
    private long serverTickTimeNanos = 0;
    private long clientTickTimeNanos = 0;
    private long renderTimeNanos = 0;
    private long encodeTimeNanos = 0;
    private long downloadTimeNanos = 0;
    private boolean patreonLinkClicked = false;
    private  static AsyncDepthVideoWriter depthWriter = null;
    private  static ByteBuffer linearDepthBuffer = null;
    private  static FloatBuffer rawDepthBuffer = null;
    private  static int exportX = 0;
    private  static int exportY = 0;
    private  static float nearPlane = 0;
    private  static float farPlane = 0;
    private int extraDummyFrames = 0;

    public int progressCount = 0;
    public int progressOutOf = 0;

    private double currentTickDouble = 0.0;

    private static int currenttick;

    private double audioSamples = 0.0;

    private final AtomicBoolean finishedServerTick = new AtomicBoolean(false);

    public static final int SRC_PIXEL_FORMAT = avutil.AV_PIX_FMT_RGBA;

    public ExportJob(ExportSettings settings) {
        this.settings = settings;
        this.particleRandom = this.settings.resetRng() ? new Random(2000) : null;
    }

    public boolean isRunning() {
        return this.running;
    }

    public void onFinishedServerTick() {
        this.finishedServerTick.set(true);
    }

    public boolean shouldChangeFramebufferSize() {
        return this.running && this.shouldChangeFramebufferSize;
    }

    public int getWidth() {
        return this.settings.resolutionX() * (this.settings.ssaa() ? 2 : 1);
    }

    public int getHeight() {
        return this.settings.resolutionY() * (this.settings.ssaa() ? 2 : 1);
    }

    public double getCurrentTickDouble() {
        return this.currentTickDouble;
    }

    public Random getParticleRandom() {
        return this.particleRandom;
    }

    public ExportSettings getSettings() {
        return this.settings;
    }

    public void run() {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (this.running || replayServer == null) {
            throw new IllegalStateException("run() called twice");
        }
        this.running = true;
        Minecraft.getInstance().mouseHandler.releaseMouse();
        Minecraft.getInstance().getSoundManager().stop();

        TaskbarManager.launchTaskbarManager();

        UUID uuid = UUID.randomUUID();

        String tempFileName = "replay_export_temp/" + uuid + "." + this.settings.container().extension();
        Path exportTempFile = Path.of(tempFileName);
        Path exportTempFolder = exportTempFile.getParent();
        TextureTarget infoRenderTarget = null;

        int oldGuiScale = Minecraft.getInstance().options.guiScale().get();

        this.extraDummyFrames = Flashback.getConfig().exportRenderDummyFrames[0];

        try {
            Files.createDirectories(exportTempFolder);

            RenderTarget mainTarget = Minecraft.getInstance().mainRenderTarget;
            infoRenderTarget = new TextureTarget(mainTarget.width, mainTarget.height, false, Minecraft.ON_OSX);

            try (VideoWriter encoder = createVideoWriter(this.settings, tempFileName);
                 SaveableFramebufferQueue downloader = new SaveableFramebufferQueue(this.settings.resolutionX(), this.settings.resolutionY())) {
                int height = this.settings.resolutionY();

                int width = this.settings.resolutionX();
                exportX = width;
                exportY = height;
                doExport(encoder, downloader, infoRenderTarget,Path.of(this.settings.output().toAbsolutePath().toString().substring(0, this.settings.output().toAbsolutePath().toString().indexOf('.'))), "CJ.json");
            }

            if (this.settings.container() != VideoContainer.PNG_SEQUENCE) {
                Files.move(exportTempFile, this.settings.output(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            this.running = false;
            this.shouldChangeFramebufferSize = false;

            // Reset display size
            Minecraft.getInstance().options.guiScale().set(oldGuiScale);
            Minecraft.getInstance().resizeDisplay();

            // Refreeze server & client
            replayServer.replayPaused = true;
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                level.tickRateManager().setFrozen(true);
            }

            Minecraft.getInstance().getSoundManager().stop();
            if(Flashback.getConfig().depthexport) {
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ALLAY_THROW, 1.0f));
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f));
            } else{
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME, 1.0f));
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.0f));
            }
            try {
                Files.deleteIfExists(exportTempFile);
            } catch (IOException ignored) {}

            try {
                boolean empty;
                try (var stream = Files.newDirectoryStream(exportTempFolder)) {
                    empty = !stream.iterator().hasNext();
                }
                if (empty) {
                    Files.deleteIfExists(exportTempFolder);
                }
            } catch (IOException ignored) {}

            if (infoRenderTarget != null) {
                infoRenderTarget.destroyBuffers();
            }
        }
    }

    private static VideoWriter createVideoWriter(ExportSettings settings, String tempFileName) {
        if (settings.container() == VideoContainer.PNG_SEQUENCE) {
            return new PNGSequenceVideoWriter(settings);
        } else {
            return new AsyncFFmpegVideoWriter(settings, tempFileName);
        }
    }

    private static ModelPart getNamedModelPart(net.minecraft.client.model.EntityModel<?> model, String partName) {
        Class<?> currentClass = model.getClass();
        while (currentClass != null) {
            try {
                java.lang.reflect.Field field = currentClass.getDeclaredField(partName);
                field.setAccessible(true);
                Object part = field.get(model);
                if (part instanceof ModelPart) {

                    return (ModelPart) part;
                }
                return null; // Return null if part is not a ModelPart
            } catch (NoSuchFieldException e) {
                // Field not found in this class, go to the superclass
                currentClass = currentClass.getSuperclass();
            } catch (IllegalAccessException e) {
                System.err.println("Could not access field: " + partName + " in model: " + model.getClass().getName());
                e.printStackTrace();
                return null;
            }
        }
        System.err.println("Could not find ModelPart with name: " + partName + " in model hierarchy of: " + model.getClass().getName());
        return null;
    }
    public static void captureDepthFrame(org.joml.Matrix4f projectionMatrix) {
        if (!Flashback.getConfig().depthexport) return;
        if (!PerfectFrames.isCapturingDepth()) return;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        if (mainTarget == null || !mainTarget.useDepth) return;

        int texW = mainTarget.width;
        int texH = mainTarget.height;

        // --- BUFFER MGMT ---
        if (rawDepthBuffer == null || rawDepthBuffer.capacity() < texW * texH) {
            rawDepthBuffer = org.lwjgl.BufferUtils.createFloatBuffer(texW * texH);
            linearDepthBuffer = java.nio.ByteBuffer.allocateDirect(exportX * exportY * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        }
        rawDepthBuffer.clear();

        // --- HIJACK & READ ---
        int prevFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);

        org.lwjgl.opengl.GL11.glReadPixels(0, 0, texW, texH, org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11.GL_FLOAT, rawDepthBuffer);

        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, prevFbo);

        // --- MATRIX MATH ---
        // Calculate the Inverse Projection Matrix once per frame
        org.joml.Matrix4f invProj = new org.joml.Matrix4f(projectionMatrix).invert();
        org.joml.Vector4f pos = new org.joml.Vector4f();

        float stepX = (float) texW / exportX;
        float stepY = (float) texH / exportY;

        for (int y = 0; y < exportY; y++) {
            for (int x = 0; x < exportX; x++) {
                int srcX = Math.min((int) ((x + 0.5f) * stepX), texW - 1);
                int srcY = Math.min((int) ((y + 0.5f) * stepY), texH - 1);
                float z_raw = rawDepthBuffer.get(srcY * texW + srcX);

                float depthInMeters;

                if (z_raw >= 1.0f || z_raw <= 0.000001f) {
                    depthInMeters = 100000.0f; // Sky/Void
                } else {
                    // 1. Convert to Normalized Device Coordinates (NDC)
                    // x and y don't strictly matter for linear Z, but we use 0,0 (center)
                    float z_ndc = z_raw * 2.0f - 1.0f;

                    // 2. Unproject from Clip Space to View Space
                    pos.set(0, 0, z_ndc, 1.0f);
                    pos.mul(invProj);

                    // 3. Perspective Division
                    // In View Space, the Z coordinate is the distance from the camera
                    // OpenGL View Space is -Z forward, so we negate it.
                    depthInMeters = -pos.z / pos.w;
                }

                linearDepthBuffer.putFloat(((exportY - 1 - y) * exportX + x) * 4, depthInMeters);
            }
        }

        linearDepthBuffer.rewind();
        if (depthWriter != null) {
            depthWriter.encode(linearDepthBuffer, exportX, exportY, currenttick);
        }
    }
    public static float getExactFov(EditorScene scene, float currentTick, boolean useHighPrecision, RealTimeMapping mapping) {
        // 1. Find the FOV Track
        KeyframeTrack fovTrack = null;
        for (KeyframeTrack track : scene.keyframeTracks) {
            // Check if this track controls FOV.

            if (track.enabled && track.keyframeType.keyframeChangeType() == KeyframeChangeFov.class) {
                fovTrack = track;
                break;
            }
        }

        // Return default if no track is active
        if (fovTrack == null) return 70.0f; // Default FOV



        KeyframeChange change = fovTrack.createKeyframeChange(currentTick, mapping);

        if (change == null) {

            return 70.0f;
        }

        if (change instanceof KeyframeChangeFov) {
            return ((com.moulberry.flashback.keyframe.change.KeyframeChangeFov) change).fov();
        }

        return 70.0f;
    }



    private void doExport(VideoWriter videoWriter, SaveableFramebufferQueue downloader, TextureTarget infoRenderTarget, Path folder, String fn) {

        if(Flashback.getConfig().depthexport) {

            int height = this.settings.resolutionY();

            int width = this.settings.resolutionX();
            exportX = width;
            exportY = height;

            // 3. Get Planes for Math
             nearPlane = 0.05f;
             farPlane = Minecraft.getInstance().options.renderDistance().get() * 16.0f;
            depthWriter = new AsyncDepthVideoWriter(
                    folder.toString().replace(".mp4", "_depth.mkv"),
                    width,
                    height
            );
        }
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer == null) {
            return;
        }

        Random random = new Random(1000);
        Random mathRandom = this.settings.resetRng() ? Utils.getInternalMathRandom() : null;

        this.setup(replayServer);
        this.updateRandoms(random, mathRandom);

        shouldChangeFramebufferSize = true;
        // Double gui scale if using SSAA which doubles resolution
        if (this.settings.ssaa()) {
            Minecraft.getInstance().options.guiScale().set(Minecraft.getInstance().options.guiScale().get() * 2);
        }
        Minecraft.getInstance().resizeDisplay();

        List<TickInfo> ticks = calculateTicks(this.settings.editorState(), this.settings.startTick(), this.settings.endTick(), this.settings.framerate());

        int clientTickCount = 0;

        this.renderStartTime = System.currentTimeMillis();

        double lastClientTickDouble = 0;

        List<Map<String, Object>> allCameraKeyframes = new ArrayList<>();
        List<Map<String, Object>> trackedData = new ArrayList<>();

        Gson gson = new GsonBuilder().setPrettyPrinting().create();


        for (int tickIndex = 0; tickIndex < ticks.size(); tickIndex++) {
            TickInfo tickInfo = ticks.get(tickIndex);
            boolean frozen = tickInfo.frozen;
            this.currentTickDouble = tickInfo.serverTick;
            int targetServerTick = this.settings.startTick() + (int) currentTickDouble;

            float deltaTicksFloat = (float)(tickInfo.clientTick - lastClientTickDouble);
            lastClientTickDouble = tickInfo.clientTick;
            double partialClientTick = tickInfo.clientTick - (int) tickInfo.clientTick;

            // Wait until server is on correct replay tick
            long start = System.nanoTime();
            this.setServerTickAndWait(replayServer, targetServerTick, false);
            serverTickTimeNanos += System.nanoTime() - start;

            // Tick client
            while (clientTickCount < (int) tickInfo.clientTick) {
                start = System.nanoTime();
                this.updateRandoms(random, mathRandom);
                this.runClientTick(frozen);
                clientTickTimeNanos += System.nanoTime() - start;

                clientTickCount += 1;
            }

            this.updateClientFreeze(frozen);

            DeltaTracker.Timer timer = Minecraft.getInstance().timer;
            timer.updateFrozenState(frozen);
            timer.updatePauseState(false);
            timer.deltaTicks = deltaTicksFloat;
            timer.realtimeDeltaTicks = deltaTicksFloat;
            timer.deltaTickResidual = (float) partialClientTick;
            timer.pausedDeltaTickResidual = (float) partialClientTick;



            AccurateEntityPositionHandler.apply(Minecraft.getInstance().level, timer);
            KeyframeHandler keyframeHandler = new MinecraftKeyframeHandler(Minecraft.getInstance());
            this.settings.editorState().applyKeyframes(keyframeHandler, (float)(this.settings.startTick() + currentTickDouble));

            long pauseScreenStart = System.currentTimeMillis();
            int additionalDummyFrames = this.extraDummyFrames;
            while (Minecraft.getInstance().getOverlay() != null || Minecraft.getInstance().screen != null || additionalDummyFrames > 0) {
                if (Minecraft.getInstance().getOverlay() != null || Minecraft.getInstance().screen != null) {
                    this.runClientTick(frozen);
                }
                if (additionalDummyFrames > 0) {
                    additionalDummyFrames -= 1;
                }

                Window window = Minecraft.getInstance().getWindow();
                RenderTarget renderTarget = Minecraft.getInstance().mainRenderTarget;
                renderTarget.bindWrite(true);
                RenderSystem.clear(16640, Minecraft.ON_OSX);
                Minecraft.getInstance().gameRenderer.render(Minecraft.getInstance().timer, true);
                renderTarget.unbindWrite();

                this.shouldChangeFramebufferSize = false;
                renderTarget.blitToScreen(window.getWidth(), window.getHeight());
                window.updateDisplay();
                this.shouldChangeFramebufferSize = true;

                if (Minecraft.getInstance().getOverlay() != null || Minecraft.getInstance().screen != null) {
                    LockSupport.parkNanos("waiting for pause overlay to disappear", 50_000_000L);

                    // Force remove screens/overlays after 5s/15s respectively
                    long currentTime = System.currentTimeMillis();
                    if (pauseScreenStart > currentTime) {
                        pauseScreenStart = currentTime;
                    }
                    if (currentTime - pauseScreenStart > 5000) {
                        Minecraft.getInstance().setScreen(null);
                    }
                    if (currentTime - pauseScreenStart > 15000) {
                        Minecraft.getInstance().setOverlay(null);
                    }
                }

                this.updateClientFreeze(frozen);

                timer.updateFrozenState(frozen);
                timer.updatePauseState(false);
                timer.deltaTicks = deltaTicksFloat;
                timer.realtimeDeltaTicks = deltaTicksFloat;
                timer.deltaTickResidual = (float) partialClientTick;
                timer.pausedDeltaTickResidual = (float) partialClientTick;
            }


            if (Flashback.getConfig().cjson) {

                Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
                if (camera != null) {

                    Map<String, Object> keyframeData = new HashMap<>();
                    keyframeData.put("tick", tickIndex);

                    Entity camEnt = Minecraft.getInstance().cameraEntity;

                    if (camEnt != null) {
                        // Manually LERP the position to match the exact moment of the depth capture
                        double x = camEnt.xo + (camEnt.getX() - camEnt.xo) * partialClientTick;
                        double y = camEnt.yo + (camEnt.getY() - camEnt.yo) * partialClientTick;
                        double z = camEnt.zo + (camEnt.getZ() - camEnt.zo) * partialClientTick;
                        double eyeHeight = camEnt.getEyeHeight();
                        double finalY = y + eyeHeight;
                        // Minecraft Y is Blender Z. Minecraft Z is Blender -Y.
                        // If you want to handle the swap in Java:
                        keyframeData.put("position", new double[]{x, finalY, z});
                    }

                    // Get rotation (yaw and pitch)
                    keyframeData.put("yaw", camera.getYRot() - replayServer.ShakeY);
                    keyframeData.put("pitch", camera.getXRot() - replayServer.ShakeX);
                    keyframeData.put("roll", replayServer.saveroll);




                    keyframeData.put("fov", replayServer.getEditorState().replayVisuals.overrideFovAmount);

                    allCameraKeyframes.add(keyframeData);
                }
            }
            if(Flashback.getConfig().etjson){
                if (!Flashback.trackedmodels.isEmpty()) {

                    Map<String, Object> keyframeData = new HashMap<>();

                    keyframeData.put("tick", tickIndex);

                    for (Map<String, Object> currentModelMap : Flashback.trackedmodels) {

                        for (Map.Entry<String, Object> entry : currentModelMap.entrySet()) {
                            String key = entry.getKey(); // "EntityName/PartName"

                            String[] keyParts = key.split("/");
                            String entityName = keyParts[0];
                            String partName = keyParts[1];

                            // Calculate the tick value
                            Map<String, Object> partData = new HashMap<>();

                            assert Minecraft.getInstance().level != null;
                            Entity entity =  Minecraft.getInstance().level.getEntities().get(UUID.fromString(entityName));

                            if(entity != null) {
                                // Create part data
                                EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);

                                if (partName.equals("Eyes")) {
                                    float tick = (float) (partialClientTick + 0.001);
                                    if (tick < 0.0f) {
                                        tick = 0.0f;
                                    } else if (tick > 1.0f) {
                                        tick = 1.0f;
                                    }

                                    partData.put("eyePosition", new double[]{((Entity) entity).getPosition((float) tick).x, ((Entity) entity).getPosition((float) tick).y + ((Entity) entity).getEyeHeight(), ((Entity) entity).getPosition((float) tick).z});
                                    partData.put("eyeangle", new double[]{((net.minecraft.world.entity.Entity) entity).getViewXRot((float) partialClientTick), ((net.minecraft.world.entity.Entity) entity).getViewYRot((float) partialClientTick), 0});
                                } else if (partName.equals("BlockPosition")) {
                                    partData.put("blockPosition", new double[]{((Entity) entity).getPosition((float) partialClientTick).x, ((Entity) entity).getPosition((float) partialClientTick).y, ((Entity) entity).getPosition((float) partialClientTick).z});
                                    partData.put("entityrotation", new double[]{((net.minecraft.world.entity.Entity) entity).getXRot(), ((net.minecraft.world.entity.Entity) entity).getYRot(), 0});
                                } else {
                                    if (renderer instanceof LivingEntityRenderer<?, ?> livingRenderer) {
                                        ModelPart part = getNamedModelPart(livingRenderer.getModel(), partName);
                                        partData.put("position", new double[]{((ModelPart) part).x, ((ModelPart) part).y, ((ModelPart) part).z});
                                        partData.put("rotation", new double[]{((ModelPart) part).xRot, ((ModelPart) part).yRot, ((ModelPart) part).zRot});
                                    }
                                }
                            }

                            // Find or create entity data
                            Map<String, Object> entityData = null;
                            entityData = (Map<String, Object>) keyframeData.get(entityName);
                            if (entityData == null) {

                                entityData = new HashMap<>();
                                entityData.put(partName, partData);
                                keyframeData.put(entityName, entityData);

                            } else{
                                entityData.put(partName, partData);
                                keyframeData.replace(entityName,entityData);
                            }

                        }
                    }

                    trackedData.add(keyframeData);
                }
            }
            SaveableFramebuffer saveable = downloader.take();
            exportrenderTarget = Minecraft.getInstance().mainRenderTarget;

            Runnable resetState = () -> {
                // 1. Reset Entity Interpolation (Positions)
                AccurateEntityPositionHandler.apply(Minecraft.getInstance().level, timer);

                // 2. Reset Camera Position/Rotation (Keyframes)

                KeyframeHandler handler = new MinecraftKeyframeHandler(Minecraft.getInstance());
                this.settings.editorState().applyKeyframes(handler, (float)(this.settings.startTick() + currentTickDouble));

                // 3. Reset RNG (Shake, Flicker, Particles)
                this.updateRandoms(random, mathRandom);
            };

            if(Flashback.getConfig().depthexport && depthWriter != null) {
                // A. Enable Depth Mode (Mixin will now serve Headless Skins)
                currenttick = tickIndex;
                PerfectFrames.setCaptureDepth(true);
                PerfectFrames.waitUntilFrameReady();

                resetState.run();


                // C. Setup & Render
                FogRenderer.setupNoFog();
                RenderSystem.enableCull();

                // This triggers the renderLevel mixins to capture the depth
                Minecraft.getInstance().gameRenderer.render(timer, true);

                //BECAUSE OF OUR MIXINS WE CAPTURE OUR DEPTH HERE

                // D. Cleanup
                PerfectFrames.setCaptureDepth(false);
            }




            PerfectFrames.waitUntilFrameReady();
            resetState.run();
            exportrenderTarget.bindWrite(true);
            RenderSystem.clear(16640, Minecraft.ON_OSX);
            FogRenderer.setupNoFog();
            RenderSystem.enableCull();
            // B. Bind for Color Capture

            start = System.nanoTime();
            Minecraft.getInstance().gameRenderer.render(timer, true);
            renderTimeNanos += System.nanoTime() - start;
            exportrenderTarget.unbindWrite();

            boolean cancel;

            // Capture audio if necessary
            FloatBuffer audioBuffer = null;
            if (this.settings.recordAudio()) {
                long device = Minecraft.getInstance().getSoundManager().soundEngine.library.currentDevice;

                audioSamples += 48000 / this.settings.framerate();
                int renderSamples = (int) audioSamples;
                audioSamples -= renderSamples;

                int channels = this.settings.stereoAudio() ? 2 : 1;

                audioBuffer = ByteBuffer.allocateDirect(renderSamples * 4 * channels).order(ByteOrder.nativeOrder()).asFloatBuffer();
                SOFTLoopback.alcRenderSamplesSOFT(device, audioBuffer, renderSamples);
            }

            this.shouldChangeFramebufferSize = false;
            cancel = finishFrame(exportrenderTarget, infoRenderTarget, tickIndex, ticks.size());
            this.shouldChangeFramebufferSize = true;

            submitDownloadedFrames(videoWriter, downloader, false);

            saveable.audioBuffer = audioBuffer;
            downloader.startDownload(exportrenderTarget, saveable, this.settings.ssaa());

            if (cancel) {
                ExportJobQueue.drainingQueue = false;
                break;
            }
        }




        if (Flashback.getConfig().cjson) {
            // Write camera keyframes to JSON file
            try (FileWriter writer = new FileWriter(folder + fn)) {
                gson.toJson(Map.of("keyframes", allCameraKeyframes), writer);
                System.out.println("Camera keyframes exported during video export to " + (folder + fn));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }if(Flashback.getConfig().etjson) {
            if (!Flashback.trackedmodels.isEmpty()) {

                try (FileWriter writera = new FileWriter(folder + "ET.json")) {
                    gson.toJson(Map.of("Entities", trackedData), writera);
                    System.out.println("Entity Tracking keyframes exported during video export to " + (folder + "ET.json"));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        submitDownloadedFrames(videoWriter, downloader, true);
        videoWriter.finish();
        if(Flashback.getConfig().depthexport) {
            if (depthWriter != null) {
                depthWriter.finish();
                depthWriter.close();
                SystemToast.add(Minecraft.getInstance().getToasts(), FlashbackSystemToasts.DEPTHMAP_TOAST,
                        Component.literal("DepthMap Saved"),
                        Component.literal("Format is 32-bit Float. Units = Real World Blocks."));
            }
        }
    }

    private void updateRandoms(Random random, Random mathRandom) {
        if (!this.settings.resetRng()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        long connectionSeed = random.nextLong();
        long levelSeed = random.nextLong();
        long entitySeed = random.nextLong();
        long mathSeed = random.nextLong();
        long particleSeed = random.nextLong();

        if (minecraft.getConnection() != null) {
            minecraft.getConnection().random.setSeed(connectionSeed);
        }
        if (minecraft.level != null) {
            minecraft.level.random.setSeed(levelSeed);

            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity == null) {
                    continue;
                }

                entity.getRandom().setSeed(entitySeed ^ entity.getUUID().getMostSignificantBits());
            }
        }
        if (mathRandom != null) {
            mathRandom.setSeed(mathSeed);
        }
        this.particleRandom.setSeed(particleSeed);
    }

    private void setup(ReplayServer replayServer) {
        Minecraft minecraft = Minecraft.getInstance();

        replayServer.setDesiredTickRate(20.0f, true);

        if (replayServer.getReplayTick() != this.settings.startTick()) {
            int currentTick = Math.max(0, this.settings.startTick() - 40);

            // Ensure replay server is paused at currentTick
            this.setServerTickAndWait(replayServer, currentTick, true);
            this.runClientTick(false);

            minecraft.particleEngine.clearParticles();

            // Reset all walk animations & tick counts
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof LivingEntity livingEntity) {
                    // livingEntity.walkAnimation.stop(); // unsupported in 1.21.1
                }
                entity.tickCount = 0;
            }

            // Advance until tick is at start
            while (currentTick < this.settings.startTick()) {
                currentTick += 1;
                this.setServerTickAndWait(replayServer, currentTick, true);
                this.runClientTick(false);
            }

            // Apply initial position and keyframes at start tick
            LocalPlayer player = minecraft.player;
            if (player != null) {
                Vec3 position = this.settings.initialCameraPosition();
                player.moveTo(position.x, position.y, position.z, this.settings.initialCameraYaw(), this.settings.initialCameraPitch());
                player.setDeltaMovement(Vec3.ZERO);
            }
            this.settings.editorState().applyKeyframes(new MinecraftKeyframeHandler(Minecraft.getInstance()), this.settings.startTick());
            this.runClientTick(false);

            // Ensured replay server is paused at startTick
            this.setServerTickAndWait(replayServer, this.settings.startTick(), true);
            this.runClientTick(false);
        }

        // Remove screen
        minecraft.setScreen(null);
    }

    private void setServerTickAndWait(ReplayServer replayServer, int targetTick, boolean force) {
        if (force || replayServer.getReplayTick() != targetTick) {
            this.finishedServerTick.set(false);

            replayServer.jumpToTick = targetTick;
            replayServer.replayPaused = true;
            replayServer.sendFinishedServerTick.set(true);

            while (!this.finishedServerTick.compareAndExchange(true, false)) {
                LockSupport.parkNanos("waiting for server thread", 100000L);
            }
        }
    }

    private void runClientTick(boolean frozen) {
        this.updateClientFreeze(frozen);

        Minecraft minecraft = Minecraft.getInstance();

        while (minecraft.pollTask()) {}
        this.updateClientFreeze(frozen);
        minecraft.tick();
        this.updateSoundSource(minecraft);

        this.updateClientFreeze(frozen);
    }

    private void updateSoundSource(Minecraft minecraft) {
        EditorState editorState = this.settings.editorState();
        if (editorState != null) {
            Camera audioCamera = editorState.getAudioCamera();
            if (audioCamera != null) {
                minecraft.getSoundManager().updateSource(audioCamera);
                return;
            }
        }

        minecraft.getSoundManager().updateSource(Minecraft.getInstance().gameRenderer.getMainCamera());
    }

    private void updateClientFreeze(boolean frozen) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            level.tickRateManager().setFrozen(frozen);
        }
    }

    private void submitDownloadedFrames(VideoWriter videoWriter, SaveableFramebufferQueue downloader, boolean drain) {
        SaveableFramebufferQueue.DownloadedFrame frame;
        while (true) {
            long start = System.nanoTime();
            frame = downloader.finishDownload(drain);
            downloadTimeNanos += System.nanoTime() - start;

            if (frame == null) {
                break;
            }

            start = System.nanoTime();
            videoWriter.encode(frame.image(), frame.audioBuffer());
            encodeTimeNanos += System.nanoTime() - start;
        }
    }

    private boolean finishFrame(RenderTarget framebuffer, RenderTarget infoRenderTarget, int currentFrame, int totalFrames) {
        boolean cancel = false;

        long currentTime = System.currentTimeMillis();
        if (currentTime - this.lastRenderMillis > 1000/60 || currentFrame == totalFrames) {
            this.progressCount = currentFrame;
            this.progressOutOf = totalFrames;

            Window window = Minecraft.getInstance().getWindow();

            {
                GlStateManager._colorMask(true, true, true, false);
                GlStateManager._disableDepthTest();
                GlStateManager._depthMask(false);
                GlStateManager._viewport(0, 0, window.getWidth(), window.getHeight());
                GlStateManager._disableBlend();

                Minecraft minecraft = Minecraft.getInstance();
                ShaderInstance shaderInstance = Objects.requireNonNull(minecraft.gameRenderer.blitShader, "Blit shader not loaded");
                shaderInstance.setSampler("DiffuseSampler", framebuffer.colorTextureId);
                shaderInstance.apply();
                BufferBuilder bufferBuilder = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
                bufferBuilder.addVertex(0.0F, 0.0F, 0.0F);
                bufferBuilder.addVertex(1.0F, 0.0F, 0.0F);
                bufferBuilder.addVertex(1.0F, 1.0F, 0.0F);
                bufferBuilder.addVertex(0.0F, 1.0F, 0.0F);
                BufferUploader.draw(bufferBuilder.buildOrThrow());
                shaderInstance.clear();
                GlStateManager._depthMask(true);
                GlStateManager._colorMask(true, true, true, true);
            }

            this.lastRenderMillis = currentTime;

            Font font = Minecraft.getInstance().font;
            var bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            bufferSource.endBatch();
            infoRenderTarget.bindWrite(true);

            RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);

            RenderSystem.clear(16640, Minecraft.ON_OSX);

            float guiScale = 4f;
            int scaledWidth = (int) Math.ceil(infoRenderTarget.width / guiScale);
            int scaledHeight = (int) Math.ceil(infoRenderTarget.height / guiScale);

            Matrix4f matrix4f = new Matrix4f().setOrtho(0.0f, scaledWidth, scaledHeight, 0.0f, 1000.0f, 21000.0f);
            RenderSystem.setProjectionMatrix(matrix4f, VertexSorting.ORTHOGRAPHIC_Z);

            Matrix4f matrix = new Matrix4f();
            matrix.translate(0.0f, 0.0f, -11000.0f);

            RenderSystem.disableDepthTest();

            List<String> lines = new ArrayList<>();

            if (this.settings.name() != null) {
                lines.add(this.settings.name());
                lines.add("");
            }

            lines.add("Exported Frames: " + currentFrame + "/" + totalFrames);

            long elapsed = currentTime - this.renderStartTime;
            lines.add("Time elapsed: " + formatTime(elapsed));

            if (currentFrame >= this.settings.framerate()) {
                long estimatedRemaining = (currentTime - this.renderStartTime) * (totalFrames - currentFrame) / currentFrame;
                lines.add("Estimated time remaining: " + formatTime(estimatedRemaining));
            } else {
                lines.add("Estimated time remaining: ~");
            }

            lines.add("");

            boolean debugPressed = GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_F3) != GLFW.GLFW_RELEASE;
            if (pressedDebugKey != debugPressed) {
                pressedDebugKey = debugPressed;
                if (pressedDebugKey) {
                    showingDebug = !showingDebug;
                }
            }

            if (showingDebug) {
                lines.add("ST: " + serverTickTimeNanos/1000000 + ", CT: " + clientTickTimeNanos/1000000);
                lines.add("RT: " + renderTimeNanos/1000000 + ", ET: " + encodeTimeNanos/1000000);
                lines.add("DT: " + downloadTimeNanos/1000000);
            } else {
                lines.add("Press [F3] to show debug info");
            }

            lines.add("");

            if (currentFrame == totalFrames) {
                lines.add("Saving...");
            } else if (GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE) != GLFW.GLFW_RELEASE) {
                long current = System.currentTimeMillis();
                if (this.escapeCancelStartMillis <= 0 || current < this.escapeCancelStartMillis) {
                    this.escapeCancelStartMillis = current;
                }
                if (current - this.escapeCancelStartMillis > 3000) {
                    cancel = true;
                    lines.add("Saving...");
                } else {
                    long remainingSeconds = 3 - (current - this.escapeCancelStartMillis) / 1000;
                    lines.add("Hold [ESC] to cancel (" + remainingSeconds + "s)");
                }
            } else {
                lines.add("Hold [ESC] to cancel");
                this.escapeCancelStartMillis = -1;
            }

            int x = scaledWidth / 2;
            int y = scaledHeight / 2 - font.lineHeight * (lines.size() + 1)/2;
            for (String line : lines) {
                if (line.isEmpty()) {
                    y += font.lineHeight / 2 + 1;
                } else {
                    font.drawInBatch(line, x - font.width(line)/2f, y,
                            -1, true, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, 0xF000F0);
                    y += font.lineHeight;
                }
            }

            double mouseX = ReplayUI.imguiGlfw.rawMouseX / window.getWidth() * scaledWidth;
            double mouseY = ReplayUI.imguiGlfw.rawMouseY / window.getHeight() * scaledHeight;

            y += font.lineHeight / 2 + 1;

            String iammaddiefilms = "https://www.iammaddiefilms.com/";
            int iammaddieWidth = font.width(iammaddiefilms);
            if (mouseX > x - iammaddieWidth/2f && mouseX < x + iammaddieWidth/2f && mouseY > y && mouseY < y + font.lineHeight) {
                font.drawInBatch(Component.literal(iammaddiefilms).withStyle(ChatFormatting.UNDERLINE), x - iammaddieWidth/2f, y,
                        -1, true, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, 0xF000F0);

                if (GLFW.glfwGetMouseButton(window.getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != 0) {
                    if (!this.patreonLinkClicked) {
                        this.patreonLinkClicked = true;
                        Util.getPlatform().openUri(iammaddiefilms);
                    }
                } else {
                    this.patreonLinkClicked = false;
                }
            } else {
                font.drawInBatch(iammaddiefilms, x - iammaddieWidth/2f, y,
                        -1, true, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, 0xF000F0);
                this.patreonLinkClicked = false;
            }

            y += (font.lineHeight + 6) / 2 + 1;

            String patreon = "https://www.patreon.com/flashbackmod";
            int patreonWidth = font.width(patreon);
            if (mouseX > x - patreonWidth/2f && mouseX < x + patreonWidth/2f && mouseY > y && mouseY < y + font.lineHeight) {
                font.drawInBatch(Component.literal(patreon).withStyle(ChatFormatting.UNDERLINE), x - patreonWidth/2f, y,
                    -1, true, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, 0xF000F0);

                if (GLFW.glfwGetMouseButton(window.getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != 0) {
                    if (!this.patreonLinkClicked) {
                        this.patreonLinkClicked = true;
                        Util.getPlatform().openUri(patreon);
                    }
                } else {
                    this.patreonLinkClicked = false;
                }
            } else {
                font.drawInBatch(patreon, x - patreonWidth/2f, y,
                    -1, true, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, 0xF000F0);
                this.patreonLinkClicked = false;
            }



            bufferSource.endBatch();
            RenderSystem.enableDepthTest();

            infoRenderTarget.unbindWrite();

            RenderSystem.defaultBlendFunc();
            RenderSystem.enableBlend();
            infoRenderTarget.blitToScreen(window.getWidth(), window.getHeight(), false);
            Minecraft.getInstance().getWindow().updateDisplay();
        }

        return cancel;
    }

    private String formatTime(long millis) {
        long seconds = (millis / 1000) % 60;
        long minutes = (millis / 1000 / 60) % 60;
        long hours = millis / 1000 / 60 / 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        } else if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        } else {
            return seconds + "s";
        }
    }

    private record TickInfo(double serverTick, double clientTick, boolean frozen) {}

    private static List<TickInfo> calculateTicks(EditorState editorState, int startTick, int endTick, double fps) {
        List<TickInfo> ticks = new ArrayList<>();

        ticks.add(new TickInfo(0, 0, false));

        double residual = 0;
        int currentTick = 0;

        TickrateKeyframeCapture capture = new TickrateKeyframeCapture();

        int count = endTick - startTick;
        int startFrozen = -1;
        while (currentTick <= count) {
            capture.tickrate = 20.0f;
            capture.frozen = false;
            editorState.applyKeyframes(capture, startTick + currentTick + (float) residual);

            residual += capture.tickrate / fps;

            int roundedResidual = (int) residual;
            residual -= roundedResidual;
            currentTick += roundedResidual;

            if (currentTick > count) {
                break;
            }

            double currentTickDouble = currentTick + residual;
            if (!capture.frozen) {
                startFrozen = -1;
                ticks.add(new TickInfo(currentTickDouble, currentTickDouble, false));
            } else {
                if (startFrozen < 0) {
                    startFrozen = currentTick;
                }

                if (capture.frozenDelay == 0) {
                    ticks.add(new TickInfo(currentTickDouble, Math.min(currentTickDouble, startFrozen), true));
                    continue;
                }

                double deltaFromStart = currentTickDouble - startFrozen;
                double freezeClientTicks = capture.frozenDelay <= 5 ? 0.999 : 1.999;
                double freezeDerivative = capture.frozenDelay <= 5 ? 1.0 : 0.5;
                if (deltaFromStart > capture.frozenDelay) {
                    ticks.add(new TickInfo(currentTickDouble, Math.min(currentTickDouble, startFrozen + freezeClientTicks), true));
                } else if (capture.frozenDelay == 1) {
                    ticks.add(new TickInfo(currentTickDouble, Math.min(currentTickDouble, startFrozen + freezeClientTicks), false));
                } else {
                    double freezePowerBase = FreezeSlowdownFormula.getFreezePowerBase(capture.frozenDelay, freezeDerivative);
                    double clientTicks = freezeClientTicks * FreezeSlowdownFormula.calculateFreezeClientTick(deltaFromStart, capture.frozenDelay, freezePowerBase);
                    ticks.add(new TickInfo(currentTickDouble, Math.min(currentTickDouble, startFrozen + clientTicks), false));
                }
            }
        }

        return ticks;
    }


}
