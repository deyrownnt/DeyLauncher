import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Reflection runner for @Test methods with @TempDir Path params -- sandbox stand-in for the JUnit platform. */
public class MiniRunner {
    public static void main(String[] args) throws Exception {
        Path classesDir = Path.of(args[0]);
        String filter = args.length > 1 ? args[1] : "";
        List<String> names;
        try (Stream<Path> s = Files.walk(classesDir)) {
            names = s.filter(p -> p.toString().endsWith("Test.class"))
                     .map(p -> classesDir.relativize(p).toString().replace('/', '.').replaceAll("\\.class$", ""))
                     .filter(n -> n.contains(filter)).sorted().collect(Collectors.toList());
        }
        int pass = 0, fail = 0, skip = 0;
        List<String> failures = new ArrayList<>();
        ClassLoader cl = MiniRunner.class.getClassLoader();
        for (String name : names) {
            Class<?> c = Class.forName(name, true, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (m.getAnnotation(org.junit.jupiter.api.Test.class) == null) continue;
                m.setAccessible(true);
                List<Path> temps = new ArrayList<>();
                try {
                    Constructor<?> k = c.getDeclaredConstructor(); k.setAccessible(true);
                    Object inst = k.newInstance();
                    Object[] params = new Object[m.getParameterCount()];
                    for (int i = 0; i < params.length; i++) {
                        Path t = Files.createTempDirectory("mr-"); temps.add(t); params[i] = t;
                    }
                    m.invoke(inst, params);
                    pass++;
                } catch (InvocationTargetException e) {
                    Throwable t = e.getCause();
                    if (t instanceof org.junit.jupiter.api.Assumptions.Violated) { skip++; }
                    else { fail++; failures.add(name + "#" + m.getName() + " -> " + t); if (System.getProperty("trace") != null) t.printStackTrace(); }
                } catch (Throwable t) { fail++; failures.add(name + "#" + m.getName() + " -> " + t); }
                finally { for (Path t : temps) deleteTree(t); }
            }
        }
        System.out.println("RESULT: " + pass + " passed, " + fail + " failed, " + skip + " skipped (" + names.size() + " classes)");
        for (String f : failures) System.out.println("  FAIL " + f);
        System.exit(fail == 0 ? 0 : 1);
    }
    static void deleteTree(Path p) {
        try (Stream<Path> s = Files.walk(p)) { s.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
    }
}
