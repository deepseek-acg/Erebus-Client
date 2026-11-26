package dev.erebus.client.module;

import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ColorSetting;
import dev.erebus.client.setting.ModeSetting;
import dev.erebus.client.setting.NumberSetting;

/**
 * Placeholder modules with no behavior, used only to preview the dashboard and module list
 * while real modules don't exist yet. Registered when the JVM is started with
 * {@code -Derebus.previewModules=true}; never loaded otherwise.
 */
public final class PreviewModules {
    private PreviewModules() {
    }

    public static void register(ModuleManager modules) {
        modules.register(new Preview("AutoCrystal", "Preview only. Places and breaks crystals.", Category.COMBAT) {
            {
                add(ModeSetting.of("Target", "Damage", "Damage", "Closest", "Health"));
                add(new NumberSetting("Place range", 4.5, 1, 6, 0.5));
                add(new NumberSetting("Min damage", 6, 0, 20, 1));
                add(new BoolSetting("Anti suicide", true));
            }
        });
        modules.register(new Preview("AutoTotem", "Preview only. Keeps a totem in your offhand.", Category.COMBAT) {
            {
                add(new NumberSetting("Health", 14, 1, 20, 1));
            }
        });
        modules.register(new Preview("Surround", "Preview only. Surrounds your feet with obsidian.", Category.COMBAT));
        modules.register(new Preview("ElytraFly", "Preview only. Controlled elytra flight.", Category.MOVEMENT) {
            {
                add(ModeSetting.of("Mode", "Control", "Control", "Boost", "Bounce"));
                add(new NumberSetting("Speed", 2.2, 0.5, 4, 0.1));
            }
        });
        modules.register(new Preview("ESP", "Preview only. Highlights players.", Category.RENDER) {
            {
                add(ModeSetting.of("Mode", "Box", "Box", "Outline", "Glow"));
                add(new ColorSetting("Enemy color", 0xFFFF5C6C));
                add(new ColorSetting("Friend color", 0xFF5FD38A));
            }
        });
        modules.register(new Preview("HighwayBuilder", "Preview only. Builds highways.", Category.WORLD));
        modules.register(new Preview("AutoReconnect", "Preview only. Rejoins after a kick.", Category.MISC));
    }

    private static class Preview extends Module {
        Preview(String name, String description, Category category) {
            super(name, description, category);
        }
    }
}
