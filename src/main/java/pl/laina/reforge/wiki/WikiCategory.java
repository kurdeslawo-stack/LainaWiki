package pl.laina.reforge.wiki;

import net.kyori.adventure.text.format.NamedTextColor;

public enum WikiCategory {
    WEAPONS("Broń", "weapon", NamedTextColor.GOLD),
    ARMOR("Pancerze", "armor", NamedTextColor.AQUA),
    TOOLS("Narzędzia", "tool", NamedTextColor.AQUA),
    FOOD("Jedzenie i eliksiry", "consumable", NamedTextColor.AQUA),
    MATERIALS("Materiały", "material", NamedTextColor.GOLD),
    OTHER("Pozostałe przedmioty", "misc", NamedTextColor.AQUA);

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
