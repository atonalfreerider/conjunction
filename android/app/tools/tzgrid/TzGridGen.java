import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;
import net.iakovlev.timeshape.TimeZoneEngine;

/** Standalone twin of TimeZoneGridTool (same algorithm and file format), for low-memory runs. */
public class TzGridGen {
    static final int ROOT_DEGREES = 1, ROOT_SAMPLES = 8, CHILD_SAMPLES = 4, MAX_DEPTH = 6;
    static TimeZoneEngine engine;
    static final ConcurrentHashMap<String, Integer> zones = new ConcurrentHashMap<>();
    static final AtomicInteger next = new AtomicInteger(1);

    static int zoneAt(double lat, double lon) {
        String id = engine.query(lat, lon).map(z -> z.getId()).orElse("");
        Integer i = zones.get(id);
        return i != null ? i : zones.computeIfAbsent(id, k -> next.getAndIncrement());
    }

    static void varint(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) { out.write((v & 0x7F) | 0x80); v >>>= 7; }
        out.write(v);
    }

    static void encode(ByteArrayOutputStream out, double lat0, double lon0, double size, int depth) {
        int n = depth == 0 ? ROOT_SAMPLES : CHILD_SAMPLES;
        HashMap<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double lat = lat0 + (i + 0.5) / n * size, lon = lon0 + (j + 0.5) / n * size;
            int z = zoneAt(Math.max(-89.9999, Math.min(89.9999, lat)), lon);
            counts.merge(z, 1, Integer::sum);
        }
        if (counts.size() == 1 || depth == MAX_DEPTH) {
            // Same tie-break as Kotlin maxBy (first maximum in HashMap iteration order).
            int best = -1, bestCount = -1;
            for (Map.Entry<Integer, Integer> e : counts.entrySet()) if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
            varint(out, best + 1);
            return;
        }
        varint(out, 0);
        double h = size / 2;
        encode(out, lat0, lon0, h, depth + 1);
        encode(out, lat0, lon0 + h, h, depth + 1);
        encode(out, lat0 + h, lon0, h, depth + 1);
        encode(out, lat0 + h, lon0 + h, h, depth + 1);
    }

    public static void main(String[] args) throws Exception {
        File target = new File(args[0]);
        int threads = Integer.parseInt(args[1]);
        long t0 = System.currentTimeMillis();
        zones.put("", 0);
        engine = TimeZoneEngine.initialize();
        System.out.println("loaded in " + (System.currentTimeMillis() - t0) / 1000 + "s");
        int rows = 180 / ROOT_DEGREES, cols = 360 / ROOT_DEGREES, total = rows * cols;
        byte[][] roots = new byte[total][];
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int idx = 0; idx < total; idx++) {
            final int index = idx;
            fs.add(pool.submit(() -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                encode(out, -90.0 + (index / cols) * ROOT_DEGREES, -180.0 + (index % cols) * ROOT_DEGREES, ROOT_DEGREES, 0);
                roots[index] = out.toByteArray();
                int d = done.incrementAndGet();
                if (d % 2000 == 0) System.out.println(d + " / " + total + " " + (System.currentTimeMillis() - t0) / 1000 + "s");
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (byte[] r : roots) data.write(r);
        String[] list = new String[next.get()];
        zones.forEach((k, v) -> list[v] = k);
        for (String s : list) if (s == null) throw new IllegalStateException("zone indices must be dense");
        target.getParentFile().mkdirs();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(target))))) {
            out.writeInt(0x43545A31);
            out.writeShort(1);
            out.writeUTF("timezone-boundary-builder via Timeshape 2026b.29");
            out.writeDouble(ROOT_DEGREES);
            out.writeByte(MAX_DEPTH);
            out.writeShort(list.length);
            for (String s : list) out.writeUTF(s);
            out.writeInt(data.size());
            data.writeTo(out);
        }
        System.out.println("wrote " + target.length() + " bytes, " + list.length + " zones in " + (System.currentTimeMillis() - t0) / 1000 + "s");
    }
}
