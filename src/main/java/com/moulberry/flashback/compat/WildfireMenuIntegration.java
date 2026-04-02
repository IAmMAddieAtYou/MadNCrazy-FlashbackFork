package com.moulberry.flashback.compat;

import com.wildfire.main.Gender;
import com.wildfire.main.entitydata.EntityConfig;
import com.wildfire.main.entitydata.PlayerConfig;
import com.wildfire.main.config.GenderConfigKey;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class WildfireMenuIntegration {

    public static void renderWildfireMenu(LivingEntity entity) {
        // Strict check: We only want to mess with this if it's an actual Player
        if (!(entity instanceof Player)) return;

        EntityConfig baseConfig = EntityConfig.getEntity(entity);
        if (!(baseConfig instanceof PlayerConfig config)) return;

        // --- UI STYLING: Pink Buttons ---
        // Pushing styles affects everything below it until popped.
        // Hex roughly #E06699 (R: 0.88f, G: 0.40f, B: 0.60f)
        ImGui.pushStyleColor(ImGuiCol.Button, 0.88f, 0.40f, 0.60f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.95f, 0.45f, 0.68f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.80f, 0.35f, 0.55f, 1.0f);

        // --- UI STYLING: Separate Framed Box ---
        // 0 width means fill horizontal space. 180 height. 'true' means draw a border.
        ImGui.beginChild("WildfireBox", 0, 185, true);

        // Title & Description
        ImGui.text("Wildfire Gender Settings");
        ImGui.textDisabled("Quickly adjust gender and physics for the selected entity.");
        ImGui.separator();

        // 1. Toggle Button (Using exact default of 0.6f)
        if (ImGui.button("Toggle Female Model")) {
            if (config.getGender() == Gender.FEMALE) {
                config.updateGender(Gender.MALE);
                config.updateBustSize(0.0f);
            } else {
                config.updateGender(Gender.FEMALE);
                config.updateBustSize(0.6f);
            }
            saveSafely(config);
        }

        // 2. Bust Size (Limit: 0.0 to 0.8)
        float[] currentSize = { config.getBustSize() };
        if (ImGui.sliderFloat("Bust Size", currentSize, 0.0f, 0.8f)) {
            config.updateBustSize(currentSize[0]);

            if (currentSize[0] >= 0.02f) {
                config.updateGender(Gender.FEMALE);
            } else {
                config.updateGender(Gender.MALE);
            }
            saveSafely(config);
        }

        // 4. Toggles
        boolean[] physicsEnabled = { config.hasBreastPhysics() };
        if (ImGui.checkbox("Breast Physics", physicsEnabled[0])) {
            config.updateBreastPhysics(physicsEnabled[0]);
            saveSafely(config);
        }

        ImGui.sameLine();

        boolean[] showInArmor = { config.showBreastsInArmor() };
        if (ImGui.checkbox("Show In Armor", showInArmor[0])) {
            config.updateShowBreastsInArmor(showInArmor[0]);
            saveSafely(config);
        }

        // 5. Advanced Physics Tuning
        // Bounce Limit: 0.0 to 0.5
        float[] bounce = { config.getBounceMultiplier() };
        if (ImGui.sliderFloat("Bounce Multiplier", bounce, 0.0f, 0.5f)) {
            config.updateBounceMultiplier(bounce[0]);
            saveSafely(config);
        }

        // Floppy Limit: 0.25 to 1.0
        float[] floppy = { config.getFloppiness() };
        if (ImGui.sliderFloat("Floppiness", floppy, 0.25f, 1.0f)) {
            config.updateFloppiness(floppy[0]);
            saveSafely(config);
        }

        // --- NEW: Positioning Section ---
        ImGui.separator();
        ImGui.textDisabled("Model Positioning");

        // Grab the sub-object containing the offsets
        var breasts = config.getBreasts();

        // X Offset (Separation) Limit: -1.0 to 1.0
        float[] separation = { breasts.getXOffset() };
        if (ImGui.sliderFloat("Separation", separation, -1.0f, 1.0f)) {
            breasts.updateXOffset(separation[0]);
            saveSafely(config);
        }

        // Y Offset (Height) Limit: -1.0 to 1.0
        float[] height = { breasts.getYOffset() };
        if (ImGui.sliderFloat("Height", height, -1.0f, 1.0f)) {
            breasts.updateYOffset(height[0]);
            saveSafely(config);
        }

        // Z Offset (Depth) Limit: -1.0 to 0.0
        float[] depth = { breasts.getZOffset() };
        if (ImGui.sliderFloat("Depth", depth, -1.0f, 0.0f)) {
            breasts.updateZOffset(depth[0]);
            saveSafely(config);
        }

        ImGui.endChild();
        ImGui.popStyleColor(3);
    }

    private static void saveSafely(PlayerConfig config) {
        PlayerConfig.saveGenderInfo(config);
        config.needsSync = true;
    }
}