package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.NumberSetting;
import dev.erebus.client.theme.Theme;
import dev.erebus.client.util.Colors;
import dev.erebus.client.util.Pops;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * Who you're fighting: the player you're looking at or last hit (or the nearest one in range), with their face, health
 * and absorption, armor wear, distance, totem pops this life and whether they have a totem in hand.
 */
public final class TargetElement extends HudElement {
    private static final int W = 150, H = 44;

    private final NumberSetting range = add(new NumberSetting("Range", "Show the nearest player this close when you aren't "
            + "looking at or fighting anyone.", 8, 0, 32, 1).unit("m"));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    private float shownHealth = -1;
    private int lastId = -1;
    private Player target;

    public TargetElement() {
        super("target", "Target", "The player you're fighting: face, health and absorption, armor wear, distance, totem pops "
                + "and whether they hold a totem.", false, HAnchor.CENTER, VAnchor.CENTER, 0, 46);
    }

    private Player pick(LocalPlayer me) {
        if (net.minecraft.client.Minecraft.getInstance().crosshairPickEntity instanceof Player p && p != me) return p;
        LivingEntity hit = me.getLastHurtMob();
        if (hit instanceof Player p && p.isAlive() && me.tickCount - me.getLastHurtMobTimestamp() < 200 && p.distanceTo(me) < 24) return p;
        Player best = null;
        double bd = range.get() * range.get();
        for (Player p : me.level().players()) {
            if (p == me || !p.isAlive() || p.isSpectator()) continue;
            double d = p.distanceToSqr(me);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        LocalPlayer me = ui.mc.player;
        target = me == null ? null : pick(me);
        if (target == null && !preview) {
            shownHealth = -1;
            return new int[]{0, 0};
        }
        return new int[]{W, H};
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        LocalPlayer me = ui.mc.player;
        Player t = target;
        String name = t == null ? "Opponent" : t.getName().getString();
        float hp = t == null ? 14f : t.getHealth();
        float abs = t == null ? 4f : t.getAbsorptionAmount();
        float max = t == null ? 20f : t.getMaxHealth();
        int id = t == null ? -2 : t.getId();
        if (id != lastId || shownHealth < 0) shownHealth = hp;
        lastId = id;
        shownHealth += (hp - shownHealth) * Math.min(1f, ui.dt * 6f); // the bar drains behind a hit

        if (background.get()) ui.round(x, y, W, H, ui.smallRadius(), ui.theme.hudBackground());
        // face
        if (t instanceof AbstractClientPlayer cp) {
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(ui.g, cp.getSkin(), x + 4, y + 4, 24);
        } else {
            ui.round(x + 4, y + 4, 24, 24, 3, ui.theme.track());
        }
        int tx = x + 32;
        ui.textBold(ui.ellipsize(name, W - 36), tx, y + 4, ui.theme.text());
        // health: what's left, what was just lost (fading), absorption on top
        int bw = W - 36, by = y + 15;
        float total = max + abs;
        ui.round(tx, by, bw, 6, 2, ui.theme.track());
        int lost = Math.round(bw * Math.min(1f, shownHealth / total));
        int now = Math.round(bw * Math.min(1f, hp / total));
        if (lost > now) ui.round(tx, by, lost, 6, 2, 0xFFFFFFFF);
        int hc = hp / max > 0.5f ? Theme.GOOD : hp / max > 0.25f ? Theme.WARN : Theme.BAD;
        if (now > 0) ui.round(tx, by, Math.max(2, now), 6, 2, hc);
        if (abs > 0) ui.round(tx + now, by, Math.max(2, Math.round(bw * abs / total)), 6, 2, 0xFFFFD24A);
        // details
        int pops = t == null ? 2 : Pops.count(t);
        float dist = t == null || me == null ? 3.4f : t.distanceTo(me);
        boolean totem = t == null || t.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || t.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        String d = String.format(Locale.ROOT, "%.1f HP · %.1fm", hp + abs, dist);
        ui.text(d, tx, y + 24, ui.theme.dim());
        if (pops > 0) ui.textRight(pops + (pops == 1 ? " pop" : " pops"), x + W - 4, y + 24, Theme.WARN);
        // armor wear, head to feet
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int ax = x + 4, ay = y + 34, aw = 22;
        for (int i = 0; i < 4; i++) {
            ItemStack s = t == null ? ItemStack.EMPTY : t.getItemBySlot(slots[i]);
            float f = t == null ? 0.8f - i * 0.15f : s.isEmpty() || !s.isDamageableItem() ? (s.isEmpty() ? -1f : 1f)
                    : 1f - s.getDamageValue() / (float) s.getMaxDamage();
            int sx = ax + i * (aw + 3);
            ui.rect(sx, ay, aw, 3, ui.theme.track());
            if (f >= 0) ui.rect(sx, ay, Math.max(1, Math.round(aw * f)), 3, f > 0.5f ? Theme.GOOD : f > 0.2f ? Theme.WARN : Theme.BAD);
        }
        String tot = totem ? "totem" : "no totem";
        ui.textRight(tot, x + W - 4, y + 32, totem ? Colors.lerp(ui.theme.text(), Theme.WARN, 0.6f) : Theme.GOOD);
    }
}
