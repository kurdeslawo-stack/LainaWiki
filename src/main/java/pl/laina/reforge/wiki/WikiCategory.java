package pl.laina.reforge.wiki;

import net.kyori.adventure.text.format.NamedTextColor;

public enum WikiCategory {
    WEAPONS("Broń", "weapon", NamedTextColor.RED),
    ARMOR("Pancerze", "armor", NamedTextColor.BLUE),
    TOOLS("Narzędzia", "tool", NamedTextColor.YELLOW),
    FOOD("Jedzenie i eliksiry", "consumable", NamedTextColor.GREEN),
    MATERIALS("Materiały", "material", NamedTextColor.GOLD),
    OTHER("Osobliwości", "misc", NamedTextColor.LIGHT_PURPLE);

    private final String label;
    private final String type;
    private final NamedTextColor color;

    WikiCategory(String label, String type, NamedTextColor color) {
        this.label = label;
        this.type = type;
        this.color = color;
    }

    public String label() {
        return label;
    }

    public NamedTextColor color() {
        return color;
    }

    public static WikiCategory fromType(String type) {
        if ("bow".equals(type)) return WEAPONS;
        for (WikiCategory category : values()) {
            if (category.type.equals(type)) return category;
        }
        return OTHER;
    }
}
