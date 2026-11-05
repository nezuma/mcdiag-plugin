package net.mcdiag.agent.heatmap;

import java.util.regex.Pattern;

/** Точка интереса: сундук со стартовым набором, NPC, портал в игру. */
public record Poi(String name, int x, int y, int z) {

    /** Игрок «дошёл», если он в этих пределах по горизонтали */
    public static final int RADIUS = 6;
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,32}$");

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public boolean near(int px, int pz) {
        int dx = px - x;
        int dz = pz - z;
        return dx * dx + dz * dz <= RADIUS * RADIUS;
    }

    /** Строка в config.yml: name:x:y:z */
    public String encode() {
        return name + ":" + x + ":" + y + ":" + z;
    }

    public static Poi decode(String line) {
        String[] parts = line.split(":");
        if (parts.length != 4 || !validName(parts[0])) {
            return null;
        }
        try {
            return new Poi(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
