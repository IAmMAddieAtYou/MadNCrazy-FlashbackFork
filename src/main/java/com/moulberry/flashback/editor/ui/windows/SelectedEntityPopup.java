package com.moulberry.flashback.editor.ui.windows;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.blaze3d.platform.Window;
import com.moulberry.flashback.FilePlayerSkin;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.Utils;
import com.moulberry.flashback.combo_options.GlowingOverride;
import com.moulberry.flashback.compat.WildfireMenuIntegration;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.exporting.AsyncFileDialogs;
import com.moulberry.flashback.state.EditorState;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SelectedEntityPopup {
    private static int selectedListIndex = -1;
    private static int currentComboSelection = 0;
    private static final String[] comboOptions = {"Eyes", "BlockPosition","head","rightArm","leftArm","body","leftLeg","rightLeg","hat"};
    private static boolean showTrackEntityWindow = false;
    private static ImString changeSkinInput = ImGuiHelper.createResizableImString("");
    static {
        changeSkinInput.inputData.allowedChars = "0123456789abcdef-";
    }

    private static ImString changeNameInput = ImGuiHelper.createResizableImString("");

    public static void open(Entity entity, EditorState editorState) {
        String nameOverride = editorState.nameOverride.get(entity.getUUID());
        if (nameOverride != null) {
            changeNameInput.set(nameOverride);
        } else {
            changeNameInput.set("");
        }

        GameProfile skinOverride = editorState.skinOverride.get(entity.getUUID());
        if (skinOverride != null) {
            changeSkinInput.set(skinOverride.getId().toString());
        } else {
            changeSkinInput.set("");
        }
    }

    public static void render(Entity entity, EditorState editorState) {
        UUID uuid = entity.getUUID();
        ImGui.text("Entity: " + uuid);

        ImGui.separator();

        if (ImGui.button("Look At")) {
            Minecraft.getInstance().cameraEntity.lookAt(EntityAnchorArgument.Anchor.EYES, entity.getEyePosition());
        }
        ImGui.sameLine();
        if (ImGui.button("Spectate")) {
            Minecraft.getInstance().player.connection.sendUnsignedCommand("spectate " + entity.getUUID());
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine();
        if (ImGui.button("Copy UUID")) {
            Minecraft.getInstance().keyboardHandler.setClipboard(entity.getUUID().toString());
            ReplayUI.setInfoOverlay("Copied '" + entity.getUUID() + "'");
            ImGui.closeCurrentPopup();
        }
        if (uuid.equals(editorState.audioSourceEntity)) {
            if (ImGui.button("Unset Audio Source")) {
                editorState.audioSourceEntity = null;
                editorState.markDirty();
            }
        } else if (ImGui.button("Set Audio Source")) {
            editorState.audioSourceEntity = entity.getUUID();
            editorState.markDirty();
        }




        if (ImGui.button("Open Track Entity")) {
            showTrackEntityWindow = !showTrackEntityWindow;
        }




        if (showTrackEntityWindow) {
            ImGui.begin("Track Entity", ImGuiWindowFlags.AlwaysAutoResize);

            // Combo Box
            if (ImGui.beginCombo("Track Mode", comboOptions[currentComboSelection])) {
                for (int i = 0; i < comboOptions.length; i++) {
                    boolean selected = currentComboSelection == i;
                    if (ImGui.selectable(comboOptions[i], selected)) {
                        currentComboSelection = i;
                    }
                    if (selected) ImGui.setItemDefaultFocus();
                }
                ImGui.endCombo();
            }




            // Add new tracked entity (temporary example)
            if (ImGui.button("Add Player")) {
                // Example: Add a placeholder tracked entity


                // Assuming Java
                Map<String, Object> newModelDict = new HashMap<String, Object>(); // Create a new dictionary
                if (comboOptions[currentComboSelection].toString() == "Eyes" || comboOptions[currentComboSelection].toString() == "BlockPosition") {
                    newModelDict.put(entity.getUUID().toString() + "/" +  comboOptions[currentComboSelection].toString(), comboOptions[currentComboSelection].toString()); //  May need to cast entity to ModelPart if it's not already.
                    Flashback.trackedmodels.add(newModelDict);
                } else {
                    newModelDict.put(entity.getUUID().toString() + "/" + comboOptions[currentComboSelection].toString(),  comboOptions[currentComboSelection].toString());
                    Flashback.trackedmodels.add(newModelDict);
                }


            }

            ImGui.sameLine();

            if (ImGui.button("Remove Selected") && selectedListIndex >= 0 && selectedListIndex < Flashback.trackedmodels.size()) {
                Flashback.trackedmodels.remove(selectedListIndex);
                selectedListIndex = -1;
            }

            // List Box for tracked entities
            if (ImGui.beginListBox("Current Tracked Entities")) {
                for (int i = 0; i < Flashback.trackedmodels.size(); i++) {
                    Map<String, Object> currentMap = Flashback.trackedmodels.get(i);
                    // Get the first key (assuming there's only one key-value pair per map)
                    String fullKey = currentMap.keySet().iterator().next();

                    boolean isSelected = selectedListIndex == i;
                    if (ImGui.selectable(fullKey, isSelected)) { // Use partName for display
                        selectedListIndex = i;
                    }
                }
                ImGui.endListBox();
            }

            ImGui.end();
        }

        boolean isHiddenDuringExport = editorState.hideDuringExport.contains(entity.getUUID());
        if (ImGui.checkbox("Hide During Export", isHiddenDuringExport)) {
            if (isHiddenDuringExport) {
                editorState.hideDuringExport.remove(entity.getUUID());
            } else {
                editorState.hideDuringExport.add(entity.getUUID());
            }
            editorState.markDirty();
        }

        if (!isHiddenDuringExport) {
            if (entity instanceof AbstractClientPlayer player) {
                if (editorState.hideCape.contains(player.getUUID())) {
                    if (ImGui.checkbox("Hide Cape", true)) {
                        editorState.hideCape.remove(player.getUUID());
                    }
                } else if (player.isModelPartShown(PlayerModelPart.CAPE) && player.getSkin().capeTexture() != null) {
                    if (ImGui.checkbox("Hide Cape", false)) {
                        editorState.hideCape.add(player.getUUID());
                    }
                }

                boolean hideNametag = editorState.hideNametags.contains(entity.getUUID());
                if (ImGui.checkbox("Render Nametag", !hideNametag)) {
                    if (hideNametag) {
                        editorState.hideNametags.remove(entity.getUUID());
                    } else {
                        editorState.hideNametags.add(entity.getUUID());
                    }
                }

                if (!hideNametag) {
                    boolean changedName = ImGui.inputTextWithHint("Name##SetNameInput", player.getScoreboardName(), changeNameInput);

                    if (changedName) {
                        String string = ImGuiHelper.getString(changeNameInput);
                        if (string.isEmpty()) {
                            editorState.nameOverride.remove(entity.getUUID());
                        } else {
                            editorState.nameOverride.put(entity.getUUID(), string);
                        }
                    }

                    if (editorState.hideTeamPrefix.contains(player.getUUID())) {
                        if (ImGui.checkbox("Hide Team Prefix", true)) {
                            editorState.hideTeamPrefix.remove(player.getUUID());
                        }
                    } else {
                        PlayerTeam team = player.getTeam();
                        if (team != null && !Utils.isComponentEmpty(team.getPlayerPrefix())) {
                            if (ImGui.checkbox("Hide Team Prefix", false)) {
                                editorState.hideTeamPrefix.add(player.getUUID());
                            }
                        }
                    }

                    if (editorState.hideTeamSuffix.contains(player.getUUID())) {
                        if (ImGui.checkbox("Hide Team Suffix", true)) {
                            editorState.hideTeamSuffix.remove(player.getUUID());
                        }
                    } else {
                        PlayerTeam team = player.getTeam();
                        if (team != null && !Utils.isComponentEmpty(team.getPlayerSuffix())) {
                            if (ImGui.checkbox("Hide Team Suffix", false)) {
                                editorState.hideTeamSuffix.add(player.getUUID());
                            }
                        }
                    }

                    if (editorState.hideBelowName.contains(player.getUUID())) {
                        if (ImGui.checkbox("Hide Text Below Name", true)) {
                            editorState.hideBelowName.remove(player.getUUID());
                        }
                    } else {
                        Scoreboard scoreboard = player.getScoreboard();
                        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.BELOW_NAME);
                        if (objective != null) {
                            if (ImGui.checkbox("Hide Text Below Name", false)) {
                                editorState.hideBelowName.add(player.getUUID());
                            }
                        }
                    }
                }

                showGlowingDropdown(entity, editorState);

                ImGuiHelper.separatorWithText("Change Skin & Cape (UUID)");
                ImGui.setNextItemWidth(320);
                ImGui.inputTextWithHint("##SetSkinInput", "e.g. d0e05de7-6067-454d-beae-c6d19d886191", changeSkinInput);

                if (!changeSkinInput.isEmpty()) {
                    String string = ImGuiHelper.getString(changeSkinInput);
                    try {
                        UUID changeSkinUuid = UUID.fromString(string);
                        if (ImGui.button("Apply Skin from UUID")) {
                            ProfileResult profile = Minecraft.getInstance().getMinecraftSessionService().fetchProfile(changeSkinUuid, true);
                            editorState.skinOverride.put(entity.getUUID(), profile.profile());
                            editorState.skinOverrideFromFile.remove(entity.getUUID());
                            editorState.depthSkinOverrideFromFile.remove(entity.getUUID());
                        }
                    } catch (Exception ignored) {}
                }

                if (ImGui.button("Upload Skin from File")) {
                    Path gameDir = FabricLoader.getInstance().getGameDir();
                    CompletableFuture<String> future = AsyncFileDialogs.openFileDialog(gameDir.toString(),
                        "Skin Texture", "png");
                    future.thenAccept(pathStr -> {
                        if (pathStr != null) {
                            editorState.skinOverride.remove(entity.getUUID());
                            editorState.skinOverrideFromFile.put(entity.getUUID(), new FilePlayerSkin(pathStr));

                            File original = new File(pathStr);
                            String folder = original.getParent();
                            String newName = "depth" + original.getName();
                            String depthPath = new File(folder, newName).getAbsolutePath();


                            editorState.depthSkinOverrideFromFile.put(entity.getUUID(), new FilePlayerSkin(depthPath));
                        }
                    });
                }

                boolean isValidSkin = false;
                UUID entityId = entity.getUUID();

// 1. Check if the map contains the key
                if (editorState.depthSkinOverrideFromFile.containsKey(entityId)) {
                    FilePlayerSkin skinObj = editorState.depthSkinOverrideFromFile.get(entityId);

                    if (skinObj == null) {
                        //System.err.println("[DEBUG] Entity " + entityId + " has a key in the map, but the FilePlayerSkin object is NULL!");
                    } else if (skinObj.getSkin() == null) {
                        //System.err.println("[DEBUG] FilePlayerSkin found, but getSkin() is NULL for entity: " + entityId);
                    } else if (skinObj.getSkin().texture() == null) {
                        //System.err.println("[DEBUG] Skin found, but texture() is NULL for entity: " + entityId);
                    } else {
                        // 2. Safe navigation successful
                        String skinPathStr = skinObj.getPath();

                        if (skinPathStr == null || skinPathStr.trim().isEmpty()) {
                            //System.err.println("[DEBUG] Texture path is null or empty for entity: " + entityId);
                        } else {
                            // 3. Final Physical File Check
                            java.nio.file.Path path = java.nio.file.Path.of(skinPathStr);
                            isValidSkin = java.nio.file.Files.exists(path);

                            if (!isValidSkin) {
                                // Only log this once in a while or on change to avoid spamming
                                //System.out.println("[DEBUG] Path exists in memory but NOT on disk: " + skinPathStr);
                            }
                        }
                    }
                } else {
                    // Optional: Log if you expected a skin to be there but it wasn't
                    // System.out.println("[DEBUG] No depth skin override found for: " + entityId);
                }

// 4. Display the result in ImGui
                if (isValidSkin) {
                    ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, "Depth Skin: Valid");
                    if (ImGui.isItemHovered()) {
                        ImGui.setTooltip("Path: " + editorState.depthSkinOverrideFromFile.get(entityId).getSkin().texture().getPath());
                    }
                } else {
                    ImGui.textColored(1.0f, 0.4f, 0.4f, 1.0f, "Depth Skin: Not Found");
                }

                if (editorState.skinOverride.containsKey(entity.getUUID()) || editorState.skinOverrideFromFile.containsKey(entity.getUUID()) || editorState.depthSkinOverrideFromFile.containsKey(entity.getUUID())) {
                    if (ImGui.button("Reset Skin")) {
                        editorState.skinOverride.remove(entity.getUUID());
                        editorState.skinOverrideFromFile.remove(entity.getUUID());
                        editorState.depthSkinOverrideFromFile.remove(entity.getUUID());
                        changeSkinInput.set("");
                    }
                }
            } else {
                showGlowingDropdown(entity, editorState);
            }


            ImGui.separator();
            ImGui.textDisabled("Extra Settings");

// The scale attribute only exists on LivingEntities (players, mobs, armor stands)
            if (entity instanceof LivingEntity livingEntity) {
                AttributeInstance scaleAttribute = livingEntity.getAttribute(Attributes.SCALE);

                if (scaleAttribute != null) {
                    // Grab the current scale (default is usually 1.0)
                    float[] currentScale = { (float) scaleAttribute.getBaseValue() };

                    // dragFloat parameters: Label, value array, drag speed, min value, max value
                    if (ImGui.dragFloat("Entity Scale", currentScale, 0.05f, 0.1f, 20.0f)) {
                        // 1. Update the live entity so you can see it change immediately
                        scaleAttribute.setBaseValue(currentScale[0]);

                        // 2. Save it to the Flashback project state permanently
                        editorState.customEntityScales.put(entity.getUUID(), currentScale[0]);
                        editorState.markDirty();
                    }

                    ImGui.sameLine();
                    if (ImGui.button("Reset Scale")) {
                        scaleAttribute.setBaseValue(1.0f);
                        editorState.customEntityScales.remove(entity.getUUID());
                        editorState.markDirty();
                    }
                }
            }


            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("wildfire_gender")) {
                if (entity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
                    // Delegate to a completely separate class.
                    // DO NOT put any Wildfire imports at the top of this current file.
                    WildfireMenuIntegration.renderWildfireMenu(livingEntity);
                }
            }
        }

//        ImGui.sameLine();
//        ImGui.button("Track Entity");
//
//        ImGui.checkbox("Force Glowing", false);
//        ImGui.sameLine();
//        ImGui.colorButton("Glow Colour", new float[4]);
//        ImGui.sameLine();
//        ImGui.text("Glow Colour");
//
//        if (entity instanceof LivingEntity) {
//            ImGui.checkbox("Show Nametag", true);
//            ImGui.checkbox("Override Nametag", false);
//        }
//        if (entity instanceof Player) {
//            ImGui.checkbox("Override Skin", false);
//        }
    }

    private static void showGlowingDropdown(Entity entity, EditorState editorState) {
        GlowingOverride glowingOverride = GlowingOverride.DEFAULT;
        if (editorState.glowingOverride.containsKey(entity.getUUID())) {
            glowingOverride = editorState.glowingOverride.get(entity.getUUID());
        }
        GlowingOverride newGlowingOverride = ImGuiHelper.enumCombo("Glowing", glowingOverride);
        if (newGlowingOverride != glowingOverride) {
            if (newGlowingOverride == GlowingOverride.DEFAULT) {
                editorState.glowingOverride.remove(entity.getUUID());
            } else {
                editorState.glowingOverride.put(entity.getUUID(), newGlowingOverride);
            }
        }
    }

}
