package dev.erebus.client.module.basefinding;

import dev.erebus.client.map.MapStore;
import dev.erebus.client.module.Category;
import dev.erebus.client.module.Module;
import dev.erebus.client.module.WorldRender;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.render.Projection;
import dev.erebus.client.render.Visuals;
import dev.erebus.client.render.font.Icon;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.util.ActivityEvents;
import dev.erebus.client.util.BlockChanges;
import dev.erebus.client.util.Chat;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shows activity the server tells you about even where you can't see it: machines running, chests opening, blocks
 * being broken, things being built, minecarts rolling, and server-wide events far away.
 *
 * <p>What it listens to, all of which comes with exact coordinates and goes through any amount of rock:
 * <ul>
 *   <li>level events, sent to players within 64 blocks: a dispenser or dropper firing, a crafter crafting, an anvil,
 *       grindstone or smithing table being used, a block being broken;</li>
 *   <li>block events, also within 64 blocks: pistons moving, chests, ender chests and shulker boxes opening, note
 *       blocks and bells, spawners spawning;</li>
 *   <li>block changes, sent for every chunk you have loaded (your whole render distance): redstone switching (powered
 *       rails, repeaters, comparators, lamps, observers, dropper triggers, hoppers), doors, trapdoors, gates and barrels
 *       opening, levers and buttons, blocks placed;</li>
 *   <li>minecarts moving on their own, as far as the server tracks them;</li>
 *   <li>global events (a wither spawning, the dragon dying, an End portal opening), which every player in the
 *       dimension hears from 32 blocks away in the real direction: each one is a bearing, and bearings from different
 *       places to the same repeating source (a wither farm) cross at it.</li>
 * </ul>
 * Activity close together is grouped into a spot labelled with what's going on there and how busy it is; a spot where
 * someone is clearly at work (a chest opened, a block broken, a lever flipped) is alerted. Your own doings, within a few
 * blocks of you, are left out.
 */
public final class ActivityRadar extends Module {
    /** What happened, and whether it means a person was there doing it (as opposed to a machine running). */
    enum Kind {
        Dispense("dispenser", 0xFFFFB347, false),
        Piston("piston", 0xFF9CA3AF, false),
        Redstone("redstone", 0xFFFF4D4D, false),
        Crafter("crafter", 0xFFFFD27A, false),
        Spawner("spawner", 0xFFB46CFF, false),
        Minecart("minecart", 0xFF7DD3FC, false),
        Note("note block", 0xFF67E8F9, false),
        Storage("storage opened", 0xFFFFC21A, true),
        Door("door", 0xFFE0B48C, true),
        Switch("lever or button", 0xFFFF7A59, true),
        Workstation("workstation", 0xFF4ADE80, true),
        Mining("block broken", 0xFFF472B6, true),
        Building("block placed", 0xFF34D399, true);

        final String label;
        final int color;
        final boolean person;

        Kind(String label, int color, boolean person) {
            this.label = label;
            this.color = color;
            this.person = person;
        }
    }

    private record Event(BlockPos pos, Kind kind, long at) {
    }

    /** A bearing to a global event: where you were and the direction it came from. */
    private record Bearing(int type, Vec3 from, Vec3 dir, long at) {
    }

    private record Spot(List<Event> events, AABB box, EnumMap<Kind, Integer> counts, double rate, double hz, boolean person,
                        long last, int depth) {
    }

    private final BoolSetting machines;
    private final BoolSetting redstone;
    private final BoolSetting people;
    private final BoolSetting building;
    private final BoolSetting minecarts;
    private final BoolSetting globals;
    private final NumberSetting ignoreNear;
    private final NumberSetting memory;
    private final BoolSetting personAlerts;
    private final BoolSetting machineAlerts;
    private final NumberSetting machineRate;
    private final BoolSetting showMarkers;
    private final BoolSetting showLabels;
    private final BoolSetting tracers;
    private final NumberSetting range;

    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final List<Bearing> bearings = new ArrayList<>();
    private final Map<Integer, Vec3> fixes = new HashMap<>();
    private final Map<Long, Long> personNotified = new HashMap<>();
    private final Map<Long, Long> machineNotified = new HashMap<>();
    private final Map<Integer, Vec3> cartLast = new HashMap<>();
    private final Map<Integer, Long> cartAt = new HashMap<>();
    /** Per cart: how far it has rolled since it started moving, and when it started. */
    private final Map<Integer, double[]> cartRun = new HashMap<>();
    /** Where a mob, item or arrow set off a plate or tripwire just now: what that sets off nearby isn't a person either. */
    private final ArrayDeque<Event> mobTriggers = new ArrayDeque<>();
    /** How far out mobs are being sent to you right now: past this a villager opening a door can't be seen. */
    private double mobSight = 48;
    /**
     * Where projectiles and falling blocks were in the last moment. The server tells you one is gone the instant it hits,
     * but the block it changed (a door a wind charge flung open, the anvil that landed) only at the end of its tick.
     */
    private final Map<Integer, Moved> movers = new HashMap<>();
    /**
     * Machine activity held back a moment: a lamp, door or dispenser can reach you before the plate or tripwire that set
     * it off (a dispenser's shot comes at once, block changes at the end of the tick), and if that turns out to be a mob's
     * doing, so is this.
     */
    private final ArrayDeque<Event> held = new ArrayDeque<>();

    private record Moved(Vec3 pos, long at, boolean falling) {
    }
    private List<Spot> spots = List.of();
    private ResourceKey<Level> dim;
    private int ticks;
    private static ActivityRadar instance;

    public ActivityRadar() {
        super("Activity Radar", "Shows what's happening where you can't see: machines running, chests opening, blocks broken or "
                + "placed, levers flipped, minecarts rolling, all with exact positions through rock, plus bearings to withers and "
                + "dragons across the dimension. Alerts when someone is at work nearby.", Category.BASEFINDING);
        group("Listen for");
        machines = add(new BoolSetting("Machines", "Dispensers and droppers firing, pistons, crafters, spawners spawning, note blocks "
                + "(within 64 blocks).", true));
        redstone = add(new BoolSetting("Redstone", "Redstone switching anywhere you have loaded: powered rails, repeaters, comparators, "
                + "lamps, observers, hoppers, dropper triggers, redstone wire.", true));
        people = add(new BoolSetting("People", "Chests, shulkers and barrels opening, doors, trapdoors and gates, levers and buttons, "
                + "anvils, grindstones and smithing tables, blocks being broken.", true));
        building = add(new BoolSetting("Building", "Blocks placed that the world doesn't place on its own (player blocks, planks, "
                + "glass, obsidian, building stone).", true));
        minecarts = add(new BoolSetting("Minecarts", "Minecarts moving with nobody in them.", true));
        globals = add(new BoolSetting("Global events", "Withers spawning, the dragon dying and End portals opening anywhere in the "
                + "dimension, as a direction; a repeating one (a wither farm) is located where the directions cross.", true));
        ignoreNear = add(new NumberSetting("Ignore near you", "Leave out what happens this close to you (your own doings).", 6, 0, 32, 1)
                .unit(" blocks"));
        memory = add(new NumberSetting("Remember", "How long activity stays shown.", 90, 10, 600, 10).unit(" s"));
        group("Alerts");
        personAlerts = add(new BoolSetting("Someone at work", "Alert when a chest is opened, a block broken or placed, a lever flipped or a "
                + "workstation used near you: someone is there right now.", true));
        machineAlerts = add(new BoolSetting("Busy machines", "Alert when a spot runs at least the rate below: a farm, a sorter or a "
                + "clock.", true));
        machineRate = add(new NumberSetting("Machine rate", "Events per second over the last 10 seconds for a machine alert.", 1, 0.2, 10,
                0.1).unit("/s").visibleWhen(machineAlerts::get));
        group("Show");
        showMarkers = add(new BoolSetting("Markers", "A mark where each event happened, fading out.", true));
        showLabels = add(new BoolSetting("Labels", "A label on each spot with what's going on, how busy and how deep.", true));
        tracers = add(new BoolSetting("Tracers", "A line to spots where someone is at work.", true));
        range = add(new NumberSetting("Range", "How far away spots are labelled.", 256, 32, 1024, 16).unit(" blocks"));
        addVisuals("Events ripple out as they happen, busy machines pulse, and bearings to global events are drawn as long rays.");
        instance = this;
        ActivityEvents.onLevel((type, pos, data, global) -> {
            ActivityRadar m = instance;
            if (m != null && m.isEnabled()) m.levelEvent(type, pos, data, global);
        });
        ActivityEvents.onBlock((pos, block, b0, b1) -> {
            ActivityRadar m = instance;
            if (m != null && m.isEnabled()) m.blockEvent(pos, block, b0, b1);
        });
        BlockChanges.listen((pos, before, after) -> {
            ActivityRadar m = instance;
            if (m != null && m.isEnabled()) m.blockChanged(pos, before, after);
        });
    }

    @Override
    protected void onEnable() {
        events.clear();
        bearings.clear();
        fixes.clear();
        spots = List.of();
        dim = null;
    }

    @Override
    protected void onDisable() {
        events.clear();
        mobTriggers.clear();
        held.clear();
        cartRun.clear();
        spots = List.of();
        info = null;
    }

    // =====================================================================================================
    // Listening
    // =====================================================================================================

    private void add(BlockPos pos, Kind kind) {
        if (mc.player == null) return;
        double near = ignoreNear.get();
        if (mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)) < near * near) return;
        Event e = new Event(pos, kind, System.currentTimeMillis());
        events.addLast(e);
        if (events.size() > 20000) events.pollFirst();
        if (kind.person && personAlerts.get()) personAlert(e);
    }

    private void levelEvent(int type, BlockPos pos, int data, boolean global) {
        if (global || type == LevelEvent.SOUND_WITHER_BOSS_SPAWN || type == LevelEvent.SOUND_DRAGON_DEATH || type == LevelEvent.SOUND_END_PORTAL_SPAWN) {
            if (globals.get()) globalEvent(type, pos);
            return;
        }
        switch (type) {
            case LevelEvent.SOUND_DISPENSER_DISPENSE, LevelEvent.SOUND_DISPENSER_FAIL, LevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH -> {
                if (machines.get()) hold(pos, Kind.Dispense); // unless it's a jungle temple trap a mob walked into
            }
            case LevelEvent.SOUND_CRAFTER_CRAFT, LevelEvent.SOUND_CRAFTER_FAIL -> {
                if (machines.get()) add(pos, Kind.Crafter);
            }
            case LevelEvent.SOUND_ANVIL_USED, LevelEvent.SOUND_ANVIL_BROKEN, LevelEvent.SOUND_GRINDSTONE_USED,
                 LevelEvent.SOUND_SMITHING_TABLE_USED, LevelEvent.END_PORTAL_FRAME_FILL -> {
                if (people.get()) add(pos, Kind.Workstation);
            }
            case LevelEvent.PARTICLES_DESTROY_BLOCK -> {
                if (!people.get() || mc.level == null) return;
                BlockState st = Block.stateById(data);
                if (natural(st) || brokenByWorld(pos, st)) return;
                add(pos, Kind.Mining); // (a piston crushing a block shows the same thing, but only on its own side, never sent)
            }
            default -> {
            }
        }
    }

    /**
     * A block broken by something other than a person: a sheep eating grass, a zombie or vindicator breaking a door, the
     * wither, a ravager, silverfish coming out of stone, an arrow shattering a pot, a boat running over a lily pad.
     */
    private boolean brokenByWorld(BlockPos pos, BlockState st) {
        Block b = st.getBlock();
        if (b instanceof net.minecraft.world.level.block.InfestedBlock
                && near(pos, 12, e -> e instanceof net.minecraft.world.entity.monster.Silverfish)) {
            return true; // a silverfish calls the others out of the stone around it
        }
        if (b == Blocks.GRASS_BLOCK && near(pos, 3, e -> e instanceof net.minecraft.world.entity.animal.sheep.Sheep)) return true;
        if (b == Blocks.DECORATED_POT || b == Blocks.LILY_PAD) {
            if (near(pos, 3, e -> e instanceof net.minecraft.world.entity.projectile.Projectile
                    || e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat)) return true;
        }
        return mobCaused(pos, 3, e -> e instanceof net.minecraft.world.entity.Mob);
    }

    private void blockEvent(BlockPos pos, Block block, int b0, int b1) {
        if (block == Blocks.PISTON || block == Blocks.STICKY_PISTON) {
            if (machines.get()) hold(pos, Kind.Piston);
        } else if (block instanceof net.minecraft.world.level.block.ChestBlock || block == Blocks.ENDER_CHEST
                || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
            // b1 = how many have it open now; copper golems open chests too, sorting items between them
            if (people.get() && b0 == 1 && b1 > 0
                    && !mobCaused(pos, 3, e -> e instanceof net.minecraft.world.entity.animal.golem.CopperGolem)) {
                add(pos, Kind.Storage);
            }
        } else if (block == Blocks.NOTE_BLOCK || block == Blocks.BELL) {
            // villagers ring the bell when raiders come, and arrows and wind charges ring it too
            if (block == Blocks.BELL && (near(pos, 3, e -> e instanceof net.minecraft.world.entity.projectile.Projectile)
                    || mobCaused(pos, 4, e -> e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager))) return;
            if (machines.get()) hold(pos, Kind.Note);
        } else if (block == Blocks.SPAWNER || block == Blocks.TRIAL_SPAWNER) {
            // it just spawned, so a player is within 16 blocks of it: unless that's you
            if (machines.get() && mc.player != null && mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > 17 * 17) add(pos, Kind.Spawner);
        }
    }

    /** Properties that only switch when redstone (or a hand) moves them. Daylight sensors and sculk are left out. */
    private static final List<Property<?>> SWITCHING = List.of(BlockStateProperties.POWERED, BlockStateProperties.LIT,
            BlockStateProperties.TRIGGERED, BlockStateProperties.EXTENDED, BlockStateProperties.ENABLED, BlockStateProperties.POWER,
            BlockStateProperties.OPEN, BlockStateProperties.ATTACHED);

    private void blockChanged(BlockPos pos, BlockState before, BlockState after) {
        if (mc.level == null) return;
        if (before.getBlock() == after.getBlock()) {
            Property<?> changed = null;
            for (Property<?> p : SWITCHING) {
                if (before.hasProperty(p) && after.hasProperty(p) && !before.getValue(p).equals(after.getValue(p))) {
                    changed = p;
                    break;
                }
            }
            if (changed == null) return;
            Block b = after.getBlock();
            if (b == Blocks.DAYLIGHT_DETECTOR || b == Blocks.REDSTONE_ORE || b == Blocks.DEEPSLATE_REDSTONE_ORE
                    || b instanceof net.minecraft.world.level.block.SculkSensorBlock || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
                    || b instanceof net.minecraft.world.level.block.CandleBlock || b instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                    || b instanceof net.minecraft.world.level.block.LightningRodBlock) { // struck by lightning
                return;
            }
            if (changed == BlockStateProperties.OPEN) {
                if (!people.get()) return;
                if (b == Blocks.BARREL) {
                    add(pos, Kind.Storage);
                } else if (after.getValue(BlockStateProperties.OPEN) && !openedByWorld(pos)) {
                    add(pos, Kind.Door); // count the opening, not the closing
                }
                return;
            }
            if (b instanceof net.minecraft.world.level.block.LeverBlock || b instanceof net.minecraft.world.level.block.ButtonBlock) {
                if (changed != BlockStateProperties.POWERED) return;
                // an arrow stuck in a wooden button, or a wind charge (a breeze's, in a trial chamber) flicking it
                if (people.get() && after.getValue(BlockStateProperties.POWERED) && !hitByProjectile(pos)) add(pos, Kind.Switch);
                return;
            }
            if (b instanceof net.minecraft.world.level.block.BasePressurePlateBlock || b instanceof net.minecraft.world.level.block.TripWireBlock
                    || b instanceof net.minecraft.world.level.block.TripWireHookBlock) {
                if (!steppedOnByPerson(pos, before, after)) return;
            } else if (b instanceof net.minecraft.world.level.block.TargetBlock) {
                return; // only ever lit up by something hitting it, almost always an arrow
            } else {
                if (redstone.get()) hold(pos, Kind.Redstone); // unless it's downstream of a plate a mob stepped on
                return;
            }
            if (redstone.get()) add(pos, Kind.Redstone);
            return;
        }
        // something placed where there was nothing (not lava meeting water, which makes cobblestone, stone, obsidian or basalt)
        if (building.get() && (before.isAir() || before.canBeReplaced()) && !after.isAir() && isBuilding(after) && !fluidMade(pos, before, after)
                && !near(pos, 2, e -> e instanceof net.minecraft.world.entity.item.FallingBlockEntity) && !moverNear(pos, 2.5, true)) { // an anvil landing
            add(pos, Kind.Building);
        }
    }

    /**
     * Villagers, illagers, piglins and zombie villagers open doors and gates; a wind charge flings them open. Past the
     * distance mobs are being sent to you, you can't see who did it, so there it only counts with a player next to it.
     */
    private boolean openedByWorld(BlockPos pos) {
        if (hitByProjectile(pos)) return true;
        if (mobCaused(pos, 3, e -> e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager
                || e instanceof net.minecraft.world.entity.monster.illager.AbstractIllager
                || e instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin
                || e instanceof net.minecraft.world.entity.monster.zombie.Zombie)) return true;
        return beyondMobSight(pos) && !playerNear(pos, 8);
    }

    /**
     * A pressure plate or tripwire set off by a person, not by a mob, an item, an arrow or a minecart. Only the press
     * counts (the release follows on its own), and when it was something else, what it sets off nearby is left out too.
     */
    private boolean steppedOnByPerson(BlockPos pos, BlockState before, BlockState after) {
        boolean pressed = after.hasProperty(BlockStateProperties.POWERED) ? after.getValue(BlockStateProperties.POWERED)
                : after.hasProperty(BlockStateProperties.POWER) ? after.getValue(BlockStateProperties.POWER) > before.getValue(BlockStateProperties.POWER)
                : after.hasProperty(BlockStateProperties.ATTACHED) && after.getValue(BlockStateProperties.ATTACHED);
        boolean world = mobCaused(pos, 2, e -> !(e instanceof Player)) || hitByProjectile(pos) || beyondMobSight(pos) && !playerNear(pos, 6);
        if (world || !pressed) { // the release follows by itself, and so does whatever it switches off
            mobTriggers.addLast(new Event(pos.immutable(), Kind.Redstone, System.currentTimeMillis()));
            if (mobTriggers.size() > 256) mobTriggers.pollFirst();
        }
        return pressed && !world;
    }

    private void hold(BlockPos pos, Kind kind) {
        held.addLast(new Event(pos.immutable(), kind, System.currentTimeMillis()));
        if (held.size() > 4096) held.pollFirst();
    }

    private void releaseHeld(long now) {
        while (!held.isEmpty() && now - held.peekFirst().at > 200) {
            Event e = held.pollFirst();
            if (!mobTriggered(e.pos)) add(e.pos, e.kind);
        }
    }

    /** Within a few seconds and blocks of a plate or tripwire something other than a person set off. */
    private boolean mobTriggered(BlockPos pos) {
        long now = System.currentTimeMillis();
        while (!mobTriggers.isEmpty() && now - mobTriggers.peekFirst().at > 4000) mobTriggers.pollFirst();
        for (Event e : mobTriggers) if (e.pos.distSqr(pos) < 16 * 16) return true;
        return false;
    }

    private boolean hitByProjectile(BlockPos pos) {
        // an arrow covers three blocks a tick, so where you last saw it can be a little short of what it hit
        return near(pos, 3.5, e -> e instanceof net.minecraft.world.entity.projectile.Projectile) || moverNear(pos, 4, false);
    }

    private boolean moverNear(BlockPos pos, double r, boolean falling) {
        long now = System.currentTimeMillis();
        Vec3 c = Vec3.atCenterOf(pos);
        for (Moved m : movers.values()) {
            if (m.falling == falling && now - m.at < 1500 && m.pos.distanceToSqr(c) < r * r) return true;
        }
        return false;
    }

    /** Remember where projectiles and falling blocks are, for {@link #movers}. */
    private void watchMovers(long now) {
        for (Entity e : mc.level.entitiesForRendering()) {
            boolean falling = e instanceof net.minecraft.world.entity.item.FallingBlockEntity;
            if (falling || e instanceof net.minecraft.world.entity.projectile.Projectile) movers.put(e.getId(), new Moved(e.position(), now, falling));
        }
        if (ticks % 20 == 0) movers.values().removeIf(m -> now - m.at > 1500);
    }

    /** An entity of this sort within r blocks (of its hitbox, so a wither's size counts), and no other player nearby to blame instead. */
    private boolean mobCaused(BlockPos pos, double r, java.util.function.Predicate<Entity> what) {
        return near(pos, r, e -> !(e instanceof Player) && what.test(e)) && !playerNear(pos, 6);
    }

    private boolean playerNear(BlockPos pos, double r) {
        return near(pos, r, e -> e instanceof Player && e != mc.player);
    }

    private boolean near(BlockPos pos, double r, java.util.function.Predicate<Entity> what) {
        return mc.level != null && !mc.level.getEntities((Entity) null, new AABB(pos).inflate(r), what::test).isEmpty();
    }

    private boolean beyondMobSight(BlockPos pos) {
        if (mc.player == null) return false;
        double dx = pos.getX() + 0.5 - mc.player.getX(), dz = pos.getZ() + 0.5 - mc.player.getZ();
        return dx * dx + dz * dz > mobSight * mobSight;
    }

    /** Lava and water make cobblestone, stone, obsidian and basalt where they meet. */
    private boolean fluidMade(BlockPos pos, BlockState before, BlockState after) {
        Block b = after.getBlock();
        if (b != Blocks.COBBLESTONE && b != Blocks.STONE && b != Blocks.OBSIDIAN && b != Blocks.BASALT) return false;
        if (!before.getFluidState().isEmpty()) return true;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!mc.level.getBlockState(pos.relative(d)).getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /** Blocks people place that the world doesn't place on its own while you watch. */
    private static boolean isBuilding(BlockState st) {
        Block b = st.getBlock();
        // endermen put down what they carry (TNT, pumpkins, melons, dirt...)
        if (!st.getFluidState().isEmpty() || natural(st) || st.is(net.minecraft.tags.BlockTags.ENDERMAN_HOLDABLE)) return false;
        return PlayerBlocks.isPlayerOnly(b) || b == Blocks.OBSIDIAN || b == Blocks.COBBLESTONE || b == Blocks.GLASS
                || st.is(net.minecraft.tags.BlockTags.PLANKS) || st.is(net.minecraft.tags.BlockTags.STONE_BRICKS)
                || b == Blocks.CRAFTING_TABLE || b == Blocks.TORCH || b == Blocks.WALL_TORCH
                || b == Blocks.LADDER || b == Blocks.SCAFFOLDING || st.is(net.minecraft.tags.BlockTags.WOOL);
    }

    /** Blocks that come and go by themselves (or by mobs). */
    private static boolean natural(BlockState st) {
        Block b = st.getBlock();
        return st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.CROPS) || b == Blocks.SNOW
                || b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.ICE || b == Blocks.FROSTED_ICE || b == Blocks.TURTLE_EGG
                || b == Blocks.POINTED_DRIPSTONE || st.is(net.minecraft.tags.BlockTags.REPLACEABLE)
                || b == Blocks.FARMLAND || b == Blocks.CACTUS || b == Blocks.SUGAR_CANE || b == Blocks.BAMBOO || b == Blocks.KELP
                || b == Blocks.KELP_PLANT || b == Blocks.CHORUS_FLOWER || b == Blocks.CHORUS_PLANT
                // grown, hatched, or popped off when what held them up went
                || st.is(net.minecraft.tags.BlockTags.FLOWERS) || st.is(net.minecraft.tags.BlockTags.SAPLINGS)
                || st.is(net.minecraft.tags.BlockTags.CAVE_VINES) || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.NETHER_WART
                || b == Blocks.COCOA || b == Blocks.BROWN_MUSHROOM || b == Blocks.RED_MUSHROOM || b == Blocks.BAMBOO_SAPLING
                || b == Blocks.SNIFFER_EGG || b == Blocks.FROGSPAWN || b == Blocks.SEA_PICKLE || b == Blocks.BIG_DRIPLEAF
                || b == Blocks.BIG_DRIPLEAF_STEM || b == Blocks.SMALL_DRIPLEAF || b == Blocks.SPORE_BLOSSOM || b == Blocks.HANGING_ROOTS
                || b == Blocks.WEEPING_VINES || b == Blocks.WEEPING_VINES_PLANT || b == Blocks.TWISTING_VINES
                || b == Blocks.TWISTING_VINES_PLANT || b == Blocks.PALE_HANGING_MOSS || b == Blocks.MOSS_CARPET
                || b == Blocks.PALE_MOSS_CARPET || b == Blocks.SNOW_BLOCK || b == Blocks.POWDER_SNOW;
    }

    // =====================================================================================================
    // Global events: bearings and where they cross
    // =====================================================================================================

    private void globalEvent(int type, BlockPos pos) {
        if (mc.player == null) return;
        Vec3 me = mc.player.position();
        Vec3 d = Vec3.atCenterOf(pos).subtract(me);
        Vec3 flat = new Vec3(d.x, 0, d.z);
        if (flat.lengthSqr() < 1e-6) return;
        // players further than 32 blocks are told a point exactly 32 blocks away (in 3D) in its direction; closer than that,
        // it's the real spot
        boolean exact = d.length() < 31.5;
        Bearing b = new Bearing(type, me, flat.normalize(), System.currentTimeMillis());
        bearings.add(b);
        if (bearings.size() > 64) bearings.remove(0);
        String what = globalName(type);
        int yaw = (int) Math.round(Math.toDegrees(Math.atan2(-flat.x, flat.z)));
        if (exact) {
            fixes.put(type, Vec3.atCenterOf(pos));
            Chat.info(what + " right here, at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ".");
            return;
        }
        Vec3 fix = triangulate(type);
        Notifications.push(name, what + " towards " + compass(flat) + " (" + yaw + "°)", Notifications.Kind.WARNING);
        if (fix != null) {
            Vec3 old = fixes.put(type, fix);
            if (old == null || old.distanceTo(fix) > 64) {
                int dist = (int) Math.round(Math.hypot(fix.x - me.x, fix.z - me.z));
                Chat.info(what + " again: its direction from here and from earlier cross near " + (int) fix.x + " " + (int) fix.z
                        + " (" + dist + " blocks away). Marked on the map.");
                if (MapStore.ready()) MapStore.addAlert(new MapStore.Mark((int) fix.x, 64, (int) fix.z, "base", what + " (triangulated)", 0));
            }
        } else {
            Chat.info(what + " towards " + compass(flat) + " (yaw " + yaw + "°). If it happens again (a wither farm), move a few hundred "
                    + "blocks sideways first and the two directions will place it.");
        }
    }

    /** Where recent bearings to this kind of event cross, if two of them are far enough apart to tell. */
    private Vec3 triangulate(int type) {
        long now = System.currentTimeMillis();
        List<Bearing> l = new ArrayList<>();
        for (Bearing b : bearings) if (b.type == type && now - b.at < 30 * 60_000L) l.add(b);
        double sx = 0, sz = 0;
        int n = 0;
        for (int i = 0; i < l.size(); i++) {
            for (int j = i + 1; j < l.size(); j++) {
                Bearing a = l.get(i), b = l.get(j);
                double cross = a.dir.x * b.dir.z - a.dir.z * b.dir.x;
                if (Math.abs(cross) < Math.sin(Math.toRadians(8))) continue; // too parallel to cross cleanly
                Vec3 w = b.from.subtract(a.from);
                double t = (w.x * b.dir.z - w.z * b.dir.x) / cross, u = (w.x * a.dir.z - w.z * a.dir.x) / cross;
                if (t < 0 || u < 0) continue; // behind one of them
                sx += a.from.x + a.dir.x * t;
                sz += a.from.z + a.dir.z * t;
                n++;
            }
        }
        return n == 0 ? null : new Vec3(sx / n, 64, sz / n);
    }

    private static String globalName(int type) {
        return switch (type) {
            case LevelEvent.SOUND_WITHER_BOSS_SPAWN -> "A wither spawned";
            case LevelEvent.SOUND_DRAGON_DEATH -> "The dragon died";
            case LevelEvent.SOUND_END_PORTAL_SPAWN -> "An End portal opened";
            default -> "A global event";
        };
    }

    private static String compass(Vec3 d) {
        double a = Math.toDegrees(Math.atan2(d.x, -d.z)); // 0 = north
        String[] n = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return n[(int) Math.floorMod(Math.round(a / 45.0), 8)];
    }

    // =====================================================================================================
    // Each tick: forget old activity, follow minecarts, group activity into spots, alert on busy machines
    // =====================================================================================================

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null) return;
        if (dim != mc.level.dimension()) {
            dim = mc.level.dimension();
            events.clear();
            cartLast.clear();
            cartRun.clear();
            mobTriggers.clear();
            mobSight = 48;
            movers.clear();
            held.clear();
        }
        long now = System.currentTimeMillis();
        long keep = (long) (memory.get() * 1000);
        while (!events.isEmpty() && now - events.peekFirst().at > keep) events.pollFirst();
        if (minecarts.get()) trackMinecarts(now);
        watchMovers(now);
        releaseHeld(now);
        if (ticks % 20 == 0) measureMobSight();
        if (++ticks % 10 == 0) {
            spots = group(now);
            if (machineAlerts.get()) machineAlerts();
            long busy = spots.stream().filter(s -> s.last > now - 10_000).count();
            info = busy > 0 ? busy + (busy == 1 ? " spot" : " spots") : null;
        }
    }

    /**
     * Carts that keep rolling on their own. A mob bumping a resting cart (in a mineshaft, say) shoves it a block or two,
     * and a cart dropped onto a slope rolls a little and stops; a cart on a rail line keeps going.
     */
    private void trackMinecarts(long now) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractMinecart cart)) continue;
            if (cart.getFirstPassenger() instanceof Player) continue; // someone riding it: they're visible anyway
            Vec3 p = cart.position();
            Vec3 last = cartLast.put(cart.getId(), p);
            if (last == null) continue;
            double step = Math.hypot(p.x - last.x, p.z - last.z);
            if (step < 0.05) { // stopped (or only falling)
                cartRun.remove(cart.getId());
                continue;
            }
            double[] run = cartRun.computeIfAbsent(cart.getId(), k -> new double[]{0, now});
            run[0] += step;
            if (run[0] < 4 || step < 0.08) continue; // not rolling far or fast enough yet to be more than a shove
            Long at = cartAt.get(cart.getId());
            if (at != null && now - at < 1000) continue; // one mark a second per cart is plenty
            cartAt.put(cart.getId(), now);
            add(cart.blockPosition(), Kind.Minecart);
        }
        if (cartLast.size() > 2000) {
            cartLast.clear();
            cartRun.clear();
            cartAt.clear();
        }
    }

    /** The farthest a mob is being sent to you: the server's entity range here, past which a mob at a door can't be seen. */
    private void measureMobSight() {
        double far = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.Mob)) continue;
            far = Math.max(far, Math.hypot(e.getX() - mc.player.getX(), e.getZ() - mc.player.getZ()));
        }
        // creep down slowly when there are fewer mobs about, never below what mobs are always sent at
        mobSight = Math.max(32, Math.max(far, mobSight * 0.995));
    }

    /** Activity within a few blocks of each other, grouped. */
    private List<Spot> group(long now) {
        Map<Long, List<Event>> cells = new HashMap<>();
        for (Event e : events) cells.computeIfAbsent(cell(e.pos), k -> new ArrayList<>()).add(e);
        Map<Long, Boolean> seen = new HashMap<>();
        List<Spot> out = new ArrayList<>();
        for (Long start : cells.keySet()) {
            if (seen.containsKey(start)) continue;
            List<Event> group = new ArrayList<>();
            ArrayDeque<Long> stack = new ArrayDeque<>();
            stack.push(start);
            seen.put(start, true);
            while (!stack.isEmpty()) {
                long c = stack.pop();
                group.addAll(cells.get(c));
                int cx = (int) (c >> 42), cy = (int) (c << 22 >> 44), cz = (int) (c << 42 >> 42); // sign-extended back
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    long n = pack(cx + dx, cy + dy, cz + dz);
                    if (cells.containsKey(n) && !seen.containsKey(n)) {
                        seen.put(n, true);
                        stack.push(n);
                    }
                }
            }
            out.add(spot(group, now));
        }
        return out;
    }

    private static long cell(BlockPos p) {
        return pack(Math.floorDiv(p.getX(), 6), Math.floorDiv(p.getY(), 6), Math.floorDiv(p.getZ(), 6));
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) y & 0xFFFFF) << 22 | ((long) z & 0x3FFFFF);
    }

    private Spot spot(List<Event> group, long now) {
        EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
        AABB box = null;
        long last = 0;
        int recentMachine = 0;
        boolean person = false;
        List<Long> machineTimes = new ArrayList<>();
        for (Event e : group) {
            counts.merge(e.kind, 1, Integer::sum);
            AABB b = new AABB(e.pos);
            box = box == null ? b : box.minmax(b);
            last = Math.max(last, e.at);
            if (e.kind.person && now - e.at < 30_000) person = true;
            if (!e.kind.person) {
                if (now - e.at < 10_000) recentMachine++;
                machineTimes.add(e.at);
            }
        }
        // how regular the machine is: the most common gap between its events, as a frequency
        double hz = 0;
        if (machineTimes.size() >= 6) {
            machineTimes.sort(Long::compare);
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < machineTimes.size(); i++) {
                long g = machineTimes.get(i) - machineTimes.get(i - 1);
                if (g > 20) gaps.add(g); // events in the same tick are one pulse
            }
            if (gaps.size() >= 4) {
                gaps.sort(Long::compare);
                hz = 1000.0 / gaps.get(gaps.size() / 2);
            }
        }
        int depth = 0;
        if (mc.level != null && box != null) {
            BlockPos c = BlockPos.containing(box.getCenter());
            int surface = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, c.getX(), c.getZ());
            depth = Math.max(0, surface - (int) box.minY);
        }
        return new Spot(group, box, counts, recentMachine / 10.0, hz, person, last, depth);
    }

    private String describe(Spot s) {
        List<Map.Entry<Kind, Integer>> l = new ArrayList<>(s.counts.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(3, l.size()); i++) parts.add(l.get(i).getValue() + " " + l.get(i).getKey().label);
        return String.join(", ", parts);
    }

    private void machineAlerts() {
        long now = System.currentTimeMillis();
        for (Spot s : spots) {
            if (s.rate < machineRate.get()) continue;
            long key = cell(BlockPos.containing(s.box.getCenter())) >>> 1;
            Long last = machineNotified.get(key);
            if (last != null && now - last < 5 * 60_000L) continue;
            machineNotified.put(key, now);
            BlockPos c = BlockPos.containing(s.box.getCenter());
            int dist = (int) Math.sqrt(mc.player.blockPosition().distSqr(c));
            String detail = describe(s) + String.format(Locale.ROOT, ", %.1f/s", s.rate) + (s.hz > 0 ? String.format(Locale.ROOT, " (%.1f Hz)", s.hz) : "")
                    + (s.depth > 3 ? ", " + s.depth + " blocks underground" : "");
            Notifications.push(name, "Machine running at " + c.getX() + " " + c.getZ() + " · " + dist + "m", Notifications.Kind.INFO);
            Chat.info("Machine running at " + c.getX() + " " + c.getY() + " " + c.getZ() + " (" + dist + "m): " + detail + ".");
        }
    }

    private void personAlert(Event e) {
        long now = System.currentTimeMillis();
        long area = cell(e.pos) >>> 2;
        Long last = personNotified.get(area);
        if (last != null && now - last < 30_000) return;
        personNotified.put(area, now);
        int dist = (int) Math.sqrt(mc.player.blockPosition().distSqr(e.pos));
        String what = switch (e.kind) {
            case Storage -> "Storage opened";
            case Door -> "Door opened";
            case Switch -> "Lever or button used";
            case Workstation -> "Workstation used";
            case Mining -> "Block broken";
            case Building -> "Block placed";
            default -> "Activity";
        };
        String where = e.pos.getX() + " " + e.pos.getY() + " " + e.pos.getZ();
        Notifications.push(name, what + " at " + where + " · " + dist + "m", Notifications.Kind.WARNING);
        Chat.info(what + " at " + where + " (" + dist + "m away): someone is there now.");
    }

    // =====================================================================================================
    // Drawing
    // =====================================================================================================

    @Override
    public void onRenderWorld(WorldRender ctx) {
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        double keep = memory.get() * 1000;
        boolean vis = visuals();
        if (showMarkers.get()) {
            int budget = 2500;
            for (Event e : events) {
                if (budget-- <= 0) break;
                double age = (now - e.at) / keep;
                if (age > 1) continue;
                float f = (float) (1 - age);
                AABB b = new AABB(e.pos).deflate(0.25);
                Gizmos.cuboid(b, GizmoStyle.strokeAndFill(Visuals.fade(e.kind.color, f), 1.5f, Visuals.alpha(e.kind.color, (int) (0x50 * f))))
                        .setAlwaysOnTop();
                if (vis && now - e.at < 1200) { // just happened: a ripple
                    float t = (now - e.at) / 1200f;
                    Visuals.ring(Vec3.atCenterOf(e.pos), 0.3 + 2.2 * t, Visuals.fade(e.kind.color, 1 - t), 2f, 24);
                }
            }
        }
        for (Spot s : spots) {
            if (s.box == null || s.box.getCenter().distanceTo(mc.player.position()) > range.get()) continue;
            boolean live = now - s.last < 10_000;
            int c = s.person && live ? 0xFFFF4D6D : 0xFFFFD27A;
            if (live && (s.person || s.rate >= machineRate.get())) {
                float p = vis ? 0.4f + 0.6f * Visuals.pulse(1.0) : 1f;
                Visuals.brackets(s.box.inflate(0.3), Visuals.fade(c, p), 2f);
                if (tracers.get() && s.person) Gizmos.line(ctx.tracerStart(), s.box.getCenter(), Visuals.fade(c, 0.8f), 1.5f).setAlwaysOnTop();
            }
        }
        if (vis && globals.get()) {
            for (Bearing b : bearings) {
                if (now - b.at > 30 * 60_000L) continue;
                Vec3 from = new Vec3(b.from.x, mc.player.getY(), b.from.z);
                Gizmos.line(from, from.add(b.dir.scale(1500)), 0x88B46CFF, 2f).setAlwaysOnTop();
            }
            for (Vec3 f : fixes.values()) Visuals.beam(new Vec3(f.x, mc.player.getY() - 20, f.z), 200, 0xFFB46CFF, 3f);
        }
    }

    @Override
    public void onRenderOverlay(dev.erebus.client.gui.Ui ui) {
        if (!showLabels.get() || mc.player == null || mc.options.hideGui) return;
        long now = System.currentTimeMillis();
        for (Spot s : spots) {
            if (s.box == null) continue;
            Vec3 c = s.box.getCenter();
            if (c.distanceTo(mc.player.position()) > range.get()) continue;
            long ago = (now - s.last) / 1000;
            String title = s.person && ago < 30 ? "Someone here" : s.rate >= machineRate.get() ? "Machine" : "Activity";
            String detail = describe(s) + (s.rate > 0 ? String.format(Locale.ROOT, " · %.1f/s", s.rate) : "")
                    + (s.depth > 3 ? " · " + s.depth + " deep" : "") + " · " + (ago < 2 ? "now" : ago + "s ago");
            int col = s.person && ago < 30 ? 0xFFFF4D6D : 0xFFFFD27A;
            float[] p = Projection.toScreen(new Vec3(c.x, s.box.maxY + 1, c.z));
            if (p != null) ui.worldLabel(Icon.RADAR, title, detail, col, p[0], p[1] - 16);
        }
        for (Map.Entry<Integer, Vec3> f : fixes.entrySet()) {
            float[] p = Projection.toScreen(new Vec3(f.getValue().x, mc.player.getY() + 30, f.getValue().z));
            int dist = (int) Math.round(Math.hypot(f.getValue().x - mc.player.getX(), f.getValue().z - mc.player.getZ()));
            if (p != null) ui.worldLabel(Icon.TARGET, globalName(f.getKey()), dist + " blocks", 0xFFB46CFF, p[0], p[1]);
        }
    }
}
