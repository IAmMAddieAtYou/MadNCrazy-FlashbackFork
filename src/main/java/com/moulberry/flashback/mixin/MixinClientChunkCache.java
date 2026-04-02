package com.moulberry.flashback.mixin;

import com.moulberry.flashback.Flashback;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache {

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void flashback$preventChunkUnloadDuringExport(ChunkPos chunkPos, CallbackInfo ci) {


        boolean isExporting = Flashback.isInReplay() || Flashback.isExporting();

        int maxRadius = Flashback.getConfig().maxchunkrender[0];

        if (!isExporting || maxRadius <= 0) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;

        int cameraChunkX = camera.getBlockPosition().getX() >> 4;
        int cameraChunkZ = camera.getBlockPosition().getZ() >> 4;

        int targetChunkX = chunkPos.x;
        int targetChunkZ = chunkPos.z;

        int distanceX = Math.abs(cameraChunkX - targetChunkX);
        int distanceZ = Math.abs(cameraChunkZ - targetChunkZ);

        if (Math.max(distanceX, distanceZ) <= maxRadius) {
            ci.cancel();
        }
    }
}