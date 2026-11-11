package net.mcdiag.agent.guard;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Что класс вызывает и какие строки содержит — из пула констант class-файла, без загрузки класса
 * в JVM. Этого хватает, чтобы заметить {@code Runtime.exec}, загрузку кода из байтов, выдачу OP и
 * адреса вебхуков, не исполняя ни строчки чужого кода.
 */
public final class ClassInfo {

    /** «java/lang/Runtime.exec» */
    public final List<String> methodRefs = new ArrayList<>();
    public final List<String> strings = new ArrayList<>();
    public final String name;

    private ClassInfo(String name) {
        this.name = name;
    }

    /** @return null, если это не class-файл */
    public static ClassInfo parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            return null;
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf = new String[count];
        int[] classIndex = new int[count];
        int[] stringIndex = new int[count];
        int[][] refs = new int[count][];
        int[][] nameAndType = new int[count][];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 7 -> classIndex[i] = in.readUnsignedShort();
                case 8 -> stringIndex[i] = in.readUnsignedShort();
                case 9, 10, 11 -> refs[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 12 -> nameAndType[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 16, 19, 20 -> in.readUnsignedShort();
                case 17, 18 -> {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("bad constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        int thisClass = in.readUnsignedShort();
        ClassInfo info = new ClassInfo(safe(utf, classIndex, thisClass));
        for (int i = 1; i < count; i++) {
            if (stringIndex[i] > 0 && stringIndex[i] < count && utf[stringIndex[i]] != null) {
                info.strings.add(utf[stringIndex[i]]);
            }
            if (refs[i] != null) {
                String owner = safe(utf, classIndex, refs[i][0]);
                int nt = refs[i][1];
                if (owner != null && nt > 0 && nt < count && nameAndType[nt] != null) {
                    String method = utf[nameAndType[nt][0]];
                    if (method != null) {
                        info.methodRefs.add(owner + "." + method);
                    }
                }
            }
        }
        return info;
    }

    private static String safe(String[] utf, int[] classIndex, int index) {
        if (index <= 0 || index >= classIndex.length) {
            return null;
        }
        int nameIndex = classIndex[index];
        return nameIndex > 0 && nameIndex < utf.length ? utf[nameIndex] : null;
    }
}
