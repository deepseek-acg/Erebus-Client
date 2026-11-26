package dev.erebus.client.module;

import dev.erebus.client.module.basefinding.OldChunks;
import dev.erebus.client.module.basefinding.PlayerBlocks;
import dev.erebus.client.module.basefinding.StashESP;
import dev.erebus.client.module.render.LogoutSpots;
import dev.erebus.client.module.world.MapModule;

/** Every module that ships with the client. */
public final class Modules {
    private Modules() {
    }

    public static void registerAll(ModuleManager modules) {
        modules.register(new MapModule());
        modules.register(new dev.erebus.client.module.world.SeedCracker());
        modules.register(new StashESP());
        modules.register(new PlayerBlocks());
        modules.register(new OldChunks());
        modules.register(new LogoutSpots());
        modules.register(new dev.erebus.client.module.combat.AutoSafeAnchor());
        modules.register(new dev.erebus.client.module.combat.AnchorAura());
        modules.register(new dev.erebus.client.module.combat.CrystalAura());
    }
}
