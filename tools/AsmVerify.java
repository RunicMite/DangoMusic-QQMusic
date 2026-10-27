import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 用 Fabric 自己用的那套 ASM 把 jar 里每个 class 都读一遍（就是崩在 ClassReader 上的） */
public class AsmVerify {

    public static void main(String[] args) throws Exception {
        int ok = 0;
        int bad = 0;
        try (ZipFile zip = new ZipFile(args[0])) {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] bytes = in.readAllBytes();
                    ClassReader reader = new ClassReader(bytes);
                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    }, 0);
                    ok++;
                } catch (Throwable t) {
                    bad++;
                    System.out.println("BAD " + e.getName() + " -> " + t);
                }
            }
        }
        System.out.println("ok=" + ok + " bad=" + bad);
        if (bad > 0) System.exit(1);
    }
}
