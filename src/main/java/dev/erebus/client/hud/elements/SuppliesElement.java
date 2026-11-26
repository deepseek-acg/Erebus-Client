package dev.erebus.client.hud.elements;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.hud.HudElement;
import dev.erebus.client.setting.BoolSetting;
import dev.erebus.client.setting.ModeSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Counts of key PvP and travel items in your inventory, shown as item icons. */
public final class SuppliesElement extends HudElement {
    private record Supply(BoolSetting toggle, Item item, int previewCount) {
    }

    private final List<Supply> supplies = new ArrayList<>();
    private final ModeSetting layout = add(ModeSetting.of("Layout", "Horizontal", "Horizontal", "Vertical"));
    private final BoolSetting hideEmpty = add(new BoolSetting("Hide empty", "Hide items you have none of.", false));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    private final List<ItemStack> shown = new ArrayList<>();
    private final List<Integer> counts = new ArrayList<>();

    public SuppliesElement() {
        super("supplies", "Supplies", "Totems, crystals, gapples and other items you carry.", true,
                HAnchor.LEFT, VAnchor.BOTTOM, 4, 46);
        supply("Totems", Items.TOTEM_OF_UNDYING, 23);
        supply("Crystals", Items.END_CRYSTAL, 448);
        supply("Gapples", Items.ENCHANTED_GOLDEN_APPLE, 61);
        supply("Obsidian", Items.OBSIDIAN, 320);
        supply("XP bottles", Items.EXPERIENCE_BOTTLE, 128);
    }

    private void supply(String name, Item item, int previewCount) {
        supplies.add(new Supply(add(new BoolSetting(name, true)), item, previewCount));
    }

    @Override
    protected int[] measure(Ui ui, boolean preview) {
        shown.clear();
        counts.clear();
        LocalPlayer p = ui.mc.player;
        if (p == null && !preview) return new int[]{0, 0};
        for (Supply s : supplies) {
            if (!s.toggle.get()) continue;
            int n = p != null ? p.getInventory().countItem(s.item) : s.previewCount;
            if (n == 0 && hideEmpty.get()) continue;
            shown.add(new ItemStack(s.item));
            counts.add(n);
        }
        if (shown.isEmpty()) return new int[]{0, 0};
        int pad = background.get() ? 3 : 0;
        if (layout.is("Vertical")) {
            int textW = 0;
            for (int c : counts) textW = Math.max(textW, ui.width(Integer.toString(c)));
            return new int[]{16 + 4 + textW + pad * 2, shown.size() * 18 - 2 + pad * 2};
        }
        return new int[]{shown.size() * 20 - 4 + pad * 2, 16 + pad * 2};
    }

    @Override
    protected void draw(Ui ui, int x, int y, boolean preview) {
        int pad = background.get() ? 3 : 0;
        if (background.get()) ui.round(x, y, width, height, ui.smallRadius(), ui.theme.hudBackground());
        boolean vertical = layout.is("Vertical");
        for (int i = 0; i < shown.size(); i++) {
            ItemStack stack = shown.get(i);
            int n = counts.get(i);
            int ix = vertical ? (alignRight() ? x + width - pad - 16 : x + pad) : x + pad + i * 20;
            int iy = vertical ? y + pad + i * 18 : y + pad;
            ui.g.renderItem(stack, ix, iy);
            String count = Integer.toString(n);
            int color = n == 0 ? dev.erebus.client.theme.Theme.BAD : 0xFFFFFFFF;
            if (vertical) {
                int tx = alignRight() ? ix - 4 - ui.width(count) : ix + 20;
                ui.textShadowed(count, tx, iy + 4, color, true);
            } else {
                ui.textShadowed(count, ix + 17 - ui.widthBold(count), iy + 9, color, true);
            }
        }
    }
}
