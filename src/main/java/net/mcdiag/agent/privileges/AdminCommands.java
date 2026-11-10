package net.mcdiag.agent.privileges;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Разбор команд выдачи прав и признаки «опасных» прав. Чистые функции — для unit-тестов. */
public final class AdminCommands {

    /** Команды, меняющие права и OP. Метка без «/» и без пространства имён («minecraft:op» → «op»). */
    private static final Set<String> ADMIN = Set.of(
            "op", "deop", "lp", "luckperms", "lpb", "lpv", "perm", "perms", "permission", "permissions",
            "pex", "manuadd", "manudel", "manuaddp", "manudelp", "mangaddp", "mangdelp", "manpromote", "mandemote");

    /** Права, дающие почти всё. Плюс любые «плагин.*» и выдача админ-групп. */
    private static final Set<String> DANGEROUS = Set.of(
            "*", "*.*", "bukkit.*", "minecraft.*", "minecraft.command.*", "bukkit.command.*",
            "bukkit.command.op", "bukkit.command.op.give", "minecraft.command.op",
            "luckperms.*", "luckperms.user.permission.set", "luckperms.user.parent.add",
            "luckperms.user.parent.set", "luckperms.group.permission.set", "luckperms.editor",
            "permissions.*", "essentials.*", "cmi.*", "worldedit.*", "worldguard.*", "multiverse.*");

    private static final Pattern ADMIN_GROUP = Pattern.compile(
            "(?i)^(admin|administrator|owner|op|operator|root|dev|developer|staff|moder|moderator|mod|helper|manager|super|главадмин|админ|модер|владелец).*");

    private AdminCommands() {
    }

    /** «/Minecraft:OP Steve» → «op». Пустая строка, если метки нет. */
    public static String label(String line) {
        String t = line.trim();
        if (t.startsWith("/")) {
            t = t.substring(1);
        }
        int space = t.indexOf(' ');
        String head = (space < 0 ? t : t.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = head.indexOf(':');
        return colon >= 0 ? head.substring(colon + 1) : head;
    }

    public static boolean isAdmin(String label) {
        return ADMIN.contains(label);
    }

    /** Ник из «op Steve» / «deop Steve», иначе null. Селекторы (@a) не разбираем. */
    public static String opTarget(String line) {
        String label = label(line);
        if (!label.equals("op") && !label.equals("deop")) {
            return null;
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2 || parts[1].startsWith("@")) {
            return null;
        }
        return parts[1];
    }

    public static boolean dangerousPermission(String permission, Set<String> extra) {
        if (permission == null) {
            return false;
        }
        String p = permission.toLowerCase(Locale.ROOT).trim();
        if (DANGEROUS.contains(p) || extra.contains(p)) {
            return true;
        }
        if (p.startsWith("group.")) {
            return adminGroup(p.substring("group.".length()));
        }
        return p.endsWith(".*") && p.indexOf('.') == p.length() - 2;
    }

    public static boolean adminGroup(String group) {
        return group != null && ADMIN_GROUP.matcher(group).matches();
    }

    /**
     * Опасна ли команда управления правами: выдача опасного права, добавление в админ-группу или OP.
     * Понимает синтаксис LuckPerms («user Steve permission set * true», «user Steve parent add admin»).
     */
    public static boolean dangerousCommand(String line, Set<String> extra) {
        String label = label(line);
        if (label.equals("op")) {
            return true;
        }
        String[] parts = line.trim().toLowerCase(Locale.ROOT).split("\\s+");
        for (int i = 1; i + 2 < parts.length; i++) {
            boolean grant = parts[i + 1].equals("set") || parts[i + 1].equals("settemp")
                    || parts[i + 1].equals("add") || parts[i + 1].equals("addtemp");
            if (!grant) {
                continue;
            }
            if (parts[i].equals("permission") && dangerousPermission(parts[i + 2], extra)) {
                return !(i + 3 < parts.length && parts[i + 3].equals("false"));
            }
            if (parts[i].equals("parent") && adminGroup(parts[i + 2])) {
                return true;
            }
        }
        return false;
    }

    /** Описание из журнала LuckPerms: «permission set * true», «parent add admin». */
    public static boolean dangerousLuckPermsAction(String description, Set<String> extra) {
        return description != null && dangerousCommand("lp x " + description, extra);
    }
}
