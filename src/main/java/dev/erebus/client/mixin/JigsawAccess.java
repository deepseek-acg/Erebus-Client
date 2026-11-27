package dev.erebus.client.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/** Read access to how a jigsaw structure picks its start piece, for the structure predictor. */
public final class JigsawAccess {
    private JigsawAccess() {
    }

    @Mixin(JigsawStructure.class)
    public interface Structure {
        @Accessor("startPool")
        Holder<StructureTemplatePool> erebus$startPool();

        @Accessor("startJigsawName")
        Optional<Identifier> erebus$startJigsawName();

        @Accessor("startHeight")
        HeightProvider erebus$startHeight();

        @Accessor("projectStartToHeightmap")
        Optional<Heightmap.Types> erebus$projectStartToHeightmap();
    }

    @Mixin(SinglePoolElement.class)
    public interface Element {
        @Accessor("template")
        Either<Identifier, StructureTemplate> erebus$template();
    }
}
