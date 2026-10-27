import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 校验 jar 内部（me/sorihiko/dango/**）的所有方法/字段引用是否都能解析。
 * 用来确认替换类之后没有留下悬空引用。
 *
 * 用法: java -cp toolsout;asm.jar LinkCheck <jar>
 */
public class LinkCheck implements Opcodes {

    static final String PREFIX = "me/sorihiko/dango/";

    public static void main(String[] args) throws Exception {
        final Map<String, Set<String>> methods = new HashMap<>();
        final Map<String, Set<String>> fields = new HashMap<>();
        final Map<String, String> supers = new HashMap<>();
        final List<String[]> refs = new ArrayList<>();
        final Set<String> classes = new HashSet<>();

        try (ZipFile zf = new ZipFile(Paths.get(args[0]).toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            List<String[]> pending = new ArrayList<>();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                final String cn = e.getName().substring(0, e.getName().length() - 6);
                classes.add(cn);
                pending.add(new String[]{cn, null});
                byte[] data = readAll(zf.getInputStream(e));
                ClassReader cr = new ClassReader(data);
                cr.accept(new ClassVisitor(ASM9) {
                    @Override
                    public void visit(int version, int access, String name, String sig, String sup, String[] itf) {
                        supers.put(name, sup == null ? "java/lang/Object" : sup);
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                        methods.computeIfAbsent(cn, k -> new HashSet<>()).add(name + desc);
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                                if (owner.startsWith(PREFIX)) refs.add(new String[]{"M", owner, n, d, cn});
                            }

                            @Override
                            public void visitFieldInsn(int op, String owner, String n, String d) {
                                if (owner.startsWith(PREFIX)) refs.add(new String[]{"F", owner, n, d, cn});
                            }
                        };
                    }

                    @Override
                    public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                        fields.computeIfAbsent(cn, k -> new HashSet<>()).add(name + ":" + desc);
                        return null;
                    }
                }, ClassReader.SKIP_DEBUG);
            }
        }

        Set<String> missing = new TreeSet<>();
        Set<String> missingClass = new TreeSet<>();
        for (String[] r : refs) {
            String kind = r[0], owner = r[1], name = r[2], desc = r[3], from = r[4];
            if (!classes.contains(owner)) {
                missingClass.add(from + " -> " + owner + (kind.equals("M") ? "." + name + desc : "." + name));
                continue;
            }
            String key = kind.equals("M") ? name + desc : name + ":" + desc;
            Map<String, Set<String>> table = kind.equals("M") ? methods : fields;
            boolean found = false;
            String cur = owner;
            for (int guard = 0; guard < 12 && cur != null; guard++) {
                Set<String> set = table.get(cur);
                if (set != null && set.contains(key)) {
                    found = true;
                    break;
                }
                if (cur.equals("java/lang/Object")) break;
                String sup = supers.get(cur);
                if (sup == null) break;               // 出 jar 了，交给 JVM/加载器
                if (!classes.contains(sup)) break;
                cur = sup;
            }
            if (!found) {
                missing.add(from + " -> " + owner + "." + (kind.equals("M") ? name + desc : name + " : " + desc));
            }
        }

        System.out.println("classes=" + classes.size() + "  refs=" + refs.size());
        if (missingClass.isEmpty() && missing.isEmpty()) {
            System.out.println("OK: 所有内部引用都能解析");
        } else {
            if (!missingClass.isEmpty()) {
                System.out.println("MISSING CLASS: " + missingClass.size());
                missingClass.stream().limit(20).forEach(s -> System.out.println("   " + s));
            }
            if (!missing.isEmpty()) {
                System.out.println("MISSING MEMBER: " + missing.size());
                missing.stream().limit(30).forEach(s -> System.out.println("   " + s));
            }
        }
        if (args.length > 1) {
            try (java.io.PrintWriter w = new java.io.PrintWriter(args[1], "UTF-8")) {
                for (String s : missingClass) w.println("CLASS " + s);
                for (String s : missing) w.println("MEMBER " + s);
            }
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
