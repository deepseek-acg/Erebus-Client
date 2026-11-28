package dev.erebus.client;

import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.setting.Setting;

import java.util.List;

/** Client-wide interface preferences shown on the dashboard's Client page. */
public final class ClientSettings {
    public final NumberSetting uiScale = new NumberSetting("Menu scale",
            "Size of the dashboard relative to Minecraft's GUI scale. It still shrinks to fit small windows.", 1.0, 0.6, 1.6, 0.05)
            .unit("x");
    public final BoolSetting menuKeyCloses = new BoolSetting("Menu key closes menu",
            "Pressing the menu key again closes the dashboard.", true);
    public final BoolSetting clickSounds = new BoolSetting("Click sounds", "Play a soft click when pressing buttons.", true);
    public final BoolSetting rememberPage = new BoolSetting("Remember last page",
            "Reopen the dashboard on the page you last used.", true);
    public final BoolSetting betterVisuals = new BoolSetting("Better visuals",
            "Extra, purely cosmetic visuals across the modules: what the combat modules are doing step by step, their blast radius "
            + "and target, a shape for each kind of find in Stash ESP, beams over structures and more. Each module also has its own "
            + "Better visuals switch; both have to be on.", true);

    // ---- World tags: the labels floating over finds, spots, players and waypoints
    public final NumberSetting tagSize = new NumberSetting("Tag size",
            "How big the labels floating in the world are (finds, lights, activity, players, waypoints). Each module that draws them "
            + "also has its own Tag size on top of this.", 0.85, 0.4, 1.6, 0.05).unit("x");
    public final NumberSetting tagOpacity = new NumberSetting("Tag opacity", "How see-through the tags are.", 90, 20, 100, 5).unit("%");
    public final BoolSetting tagShrink = new BoolSetting("Shrink with distance",
            "Tags far away are drawn smaller (down to 60% at 160 blocks), so a crowd of distant ones doesn't cover the screen.", true);
    public final BoolSetting tagCrosshairFade = new BoolSetting("Fade near crosshair",
            "A tag right where you're looking fades out, so it doesn't hide what you're aiming at.", true);
    public final ModeSetting tagDetails = new ModeSetting("Tag details",
            "The second part of a tag (distance, counts, how long ago). Near crosshair: only for the tags you look at.",
            "Always", "Always", "Near crosshair", "Never");

    public final BoolSetting messagesInChat = new BoolSetting("Also in chat",
            "Messages (finds, alerts, seeds) normally only show as Erebus notifications and on the Messages page. This also puts "
            + "them in Minecraft's chat, only for you.", false);

    public List<Setting<?>> interfaceSettings() {
        return List.of(uiScale, menuKeyCloses, clickSounds, rememberPage, betterVisuals);
    }

    public List<Setting<?>> messageSettings() {
        return List.of(messagesInChat);
    }

    public List<Setting<?>> tagSettings() {
        return List.of(tagSize, tagOpacity, tagShrink, tagCrosshairFade, tagDetails);
    }

    /** Everything that's saved. */
    public List<Setting<?>> all() {
        List<Setting<?>> l = new java.util.ArrayList<>(interfaceSettings());
        l.addAll(messageSettings());
        l.addAll(tagSettings());
        return l;
    }
}
