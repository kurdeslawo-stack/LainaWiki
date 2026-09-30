package pl.laina.reforge.wiki;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public record WikiEntry(Material material, int customModelData, String model, String title, WikiCategory category) {
    public ItemStack createIcon() {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (customModelData >= 0) meta.setCustomModelData(customModelData);
            meta.displayName(Component.text(title));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static WikiEntry wikiOnly(String title) {
        return new WikiEntry(Material.BOOK, -1, "", title, WikiCategory.OTHER);
    }
}
