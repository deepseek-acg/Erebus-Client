package dev.erebus.client.mixin;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

/**
 * A structure placement's settings, so a copy with another salt can be made (for servers that set their own
 * structure salts, as Spigot's {@code seed-village} and the like do).
 */
@Mixin(StructurePlacement.class)
public interface StructurePlacementAccess {
    @Invoker("locateOffset")
    Vec3i erebus$locateOffset();

    @Invoker("frequencyReductionMethod")
    StructurePlacement.FrequencyReductionMethod erebus$frequencyReductionMethod();

    @Invoker("frequency")
    float erebus$frequency();

    @Invoker("exclusionZone")
    Optional<StructurePlacement.ExclusionZone> erebus$exclusionZone();
}
