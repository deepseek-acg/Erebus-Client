package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.theme.Theme;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Worn armor with remaining durability. */
public final class ArmorElement extends HudElement {
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private final BoolSetting percent = add(new BoolSetting("Percentage", "Show durability as a percentage.", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    private final ItemStack[] stacks = new ItemStack[4];
    private ItemStack[] previewStacks;

    public ArmorElement() {
        super("armor", "Armor", "Your armor pieces and how much durability is left.", true,
                HAnchor.RIGHT, VAnchor.BOTTOM, 4, 46);
    }

    private ItemStack[] preview() {
        if (previewStacks == null) {
            previewStacks = new ItemStack[]{new ItemStack(Items.NETHERITE_HELMET), new ItemStack(Items.NETHERITE_CHESTPLATE),
                    new ItemStack(Items.NETHERITE_LEGGINGS), new ItemStack(Items.NETHERITE_BOOTS)};
            float[] wear = {0.08f, 0.12f, 0.36f, 0.59f};
            for (int i = 0; i < 4; i++) previewStacks[i].setDamageValue(Math.round(previewStacks[i].getMaxDamage() * wear[i]));
        }
        return previewStacks;
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        LocalPlayer p = ui.mc.player;
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            stacks[i] = p != null ? p.getItemBySlot(SLOTS[i]) : preview ? preview()[i] : ItemStack.EMPTY;
            if (!stacks[i].isEmpty()) any = true;
        }
        if (!any && !preview) return new int[]{0, 0};
        int pad = background.get() ? 3 : 0;
        return new int[]{4 * 20 - 4 + pad * 2, 16 + (percent.get() ? 10 : 0) + pad * 2};
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        int pad = background.get() ? 3 : 0;
        if (background.get()) ui.round(x, y, width, height, ui.smallRadius(), ui.theme.hudBackground());
        for (int i = 0; i < 4; i++) {
            ItemStack s = stacks[i];
            int ix = x + pad + i * 20;
            int iy = y + pad;
            if (s.isEmpty()) {
                ui.round(ix + 2, iy + 2, 12, 12, 2, 0x22FFFFFF);
                continue;
            }
            ui.g.renderItem(s, ix, iy);
            ui.g.renderItemDecorations(ui.font, s, ix, iy, "");
            if (percent.get() && s.isDamageableItem()) {
                int pct = Math.round(100f * (s.getMaxDamage() - s.getDamageValue()) / s.getMaxDamage());
                int c = pct >= 70 ? Theme.GOOD : pct >= 40 ? Theme.WARN : Theme.BAD;
                String t = pct + "%";
                ui.g.drawString(ui.font, t, ix + 8 - ui.width(t) / 2, iy + 17, c, true);
            }
        }
    }
}
