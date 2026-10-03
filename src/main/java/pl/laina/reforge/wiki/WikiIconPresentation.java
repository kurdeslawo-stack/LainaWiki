package pl.laina.reforge.wiki;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;

final class WikiIconPresentation {
    private WikiIconPresentation() { }

    static ItemStack titleOnly(ItemStack source, String articleTitle) {
        // Only the dialog copy is modified, never the player's real item.
        ItemStack copy = source.clone();
        copy.setData(DataComponentTypes.CUSTOM_NAME,
                Component.text(readableTitle(articleTitle), NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false));
        // Hide native attributes, lore, enchantments and other component tooltips.
        // Preserve actual components (including model and tooltip style) on the copy.
        var hidden = new HashSet<>(copy.getDataTypes());
        hidden.remove(DataComponentTypes.CUSTOM_NAME);
        hidden.remove(DataComponentTypes.ITEM_NAME);
        hidden.remove(DataComponentTypes.TOOLTIP_DISPLAY);
        copy.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().hideTooltip(false).hiddenComponents(hidden).build());
        return copy;
    }

    static String readableTitle(String title) {
        return title.replace('_', ' ').strip().replaceAll("\\s+", " ");
    }
}
