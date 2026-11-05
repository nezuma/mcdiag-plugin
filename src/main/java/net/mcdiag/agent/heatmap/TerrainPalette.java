package net.mcdiag.agent.heatmap;

/**
 * Цвет блока на карте, близкий к обычной карте Minecraft. В API Paper 1.19.4 нет цвета карты для
 * блока, поэтому сопоставляем по имени материала: так работает и с блоками новых версий.
 */
final class TerrainPalette {

    private static final Object[][] RULES = {
        {"WATER", 0x3f76e4}, {"LAVA", 0xd96415}, {"GRASS_BLOCK", 0x7fb238}, {"MOSS", 0x7fb238}, {"LEAVES", 0x2f7d1e},
        {"GRASS", 0x6da33a}, {"FERN", 0x6da33a}, {"SNOW", 0xf5f8fa}, {"ICE", 0xa0c8f0}, {"SANDSTONE", 0xd8cb9b},
        {"RED_SAND", 0xb5602f}, {"SAND", 0xe3d9a8}, {"GRAVEL", 0x8f8a86}, {"DIRT", 0x866043}, {"PODZOL", 0x5e4426},
        {"MYCELIUM", 0x6e5c6e}, {"MUD", 0x4a3f3c}, {"CLAY", 0xa4a8b8}, {"NETHERRACK", 0x703030}, {"SOUL", 0x4d3b2e},
        {"BLACKSTONE", 0x2a2630}, {"BASALT", 0x4a4a50}, {"END_STONE", 0xdedea0}, {"OBSIDIAN", 0x1b1426},
        {"BEDROCK", 0x565656}, {"DEEPSLATE", 0x505055}, {"QUARTZ", 0xece6dd}, {"PRISMARINE", 0x63a79a},
        {"COPPER", 0xb86f50}, {"IRON", 0xc8c8c8}, {"GOLD", 0xe0c040}, {"DIAMOND", 0x5fd8d0}, {"EMERALD", 0x2fc060},
        {"LAPIS", 0x2a4fa0}, {"REDSTONE", 0xb02020}, {"AMETHYST", 0x8a60c0}, {"BRICK", 0x9a5a48}, {"TERRACOTTA", 0xa06a50},
        {"WHITE_", 0xe8e8e8}, {"LIGHT_GRAY_", 0xa0a0a0}, {"GRAY_", 0x505050}, {"BLACK_", 0x202020}, {"BROWN_", 0x6a4a30},
        {"RED_", 0xa03030}, {"ORANGE_", 0xd08030}, {"YELLOW_", 0xd8c840}, {"LIME_", 0x80c030}, {"GREEN_", 0x507030},
        {"CYAN_", 0x308090}, {"LIGHT_BLUE_", 0x60a0d8}, {"BLUE_", 0x3050a0}, {"PURPLE_", 0x8040a0}, {"MAGENTA_", 0xb050b0},
        {"PINK_", 0xe090b0}, {"OAK", 0x9c7f4e}, {"SPRUCE", 0x6e4f2e}, {"BIRCH", 0xc8b77a}, {"JUNGLE", 0x9a6e4a},
        {"ACACIA", 0xa85a30}, {"CHERRY", 0xe0b0b0}, {"MANGROVE", 0x763030}, {"BAMBOO", 0xb8a850}, {"CRIMSON", 0x7a3040},
        {"WARPED", 0x306a60}, {"PLANKS", 0x9c7f4e}, {"LOG", 0x6e5030}, {"WOOD", 0x6e5030}, {"STONE", 0x8a8a8a},
        {"COBBLE", 0x7a7a7a}, {"ANDESITE", 0x8a8a8a}, {"GRANITE", 0x956e60}, {"DIORITE", 0xc0c0c0}, {"TUFF", 0x6c6c60},
        {"CALCITE", 0xdcdcd4}, {"GLASS", 0xbcd8e0}, {"CHEST", 0x9c7030}, {"BARREL", 0x8a6030},
    };
    private static final int DEFAULT = 0x808080;

    private TerrainPalette() {
    }

    static int colorOf(String material) {
        for (Object[] rule : RULES) {
            if (material.contains((String) rule[0])) {
                return (int) rule[1];
            }
        }
        return DEFAULT;
    }

    /** Перепад высот, как на обычной карте: склон к северу светлее, от севера темнее. */
    static int shade(int color, int height, int northHeight) {
        double factor = height > northHeight ? 1.0 : height < northHeight ? 0.71 : 0.86;
        int r = (int) (((color >> 16) & 0xff) * factor);
        int g = (int) (((color >> 8) & 0xff) * factor);
        int b = (int) ((color & 0xff) * factor);
        return (r << 16) | (g << 8) | b;
    }

    /** Тело запроса: палитра и пары «номер цвета, длина серии». */
    static String encode(String zoneId, int width, int depth, int[] pixels) {
        java.util.LinkedHashMap<Integer, Integer> palette = new java.util.LinkedHashMap<>();
        StringBuilder runs = new StringBuilder(pixels.length);
        int i = 0;
        boolean first = true;
        while (i < pixels.length) {
            int color = pixels[i];
            int length = 1;
            while (i + length < pixels.length && pixels[i + length] == color) {
                length++;
            }
            Integer index = palette.get(color);
            if (index == null) {
                index = palette.size();
                palette.put(color, index);
            }
            runs.append(first ? "" : ",").append(index).append(',').append(length);
            first = false;
            i += length;
        }
        StringBuilder sb = new StringBuilder(runs.length() + palette.size() * 9 + 128).append("{\"zone\":");
        net.mcdiag.agent.json.Json.str(sb, zoneId, 160);
        sb.append(",\"w\":").append(width).append(",\"h\":").append(depth).append(",\"palette\":[");
        first = true;
        for (Integer color : palette.keySet()) {
            sb.append(first ? "" : ",").append(color);
            first = false;
        }
        return sb.append("],\"runs\":[").append(runs).append("]}").toString();
    }
}
