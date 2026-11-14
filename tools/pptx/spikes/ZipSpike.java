import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Spike S6: copies a generated 100-slide / 60 MB package twice, once through {@link ZipFile} and
 * once through a single streaming pass, reporting wall time and peak heap for each. Standalone: not
 * part of the app build.
 *
 * <p>Payloads are incompressible random bytes, so the package really is about 60 MB on disk instead
 * of deflating to a few hundred kilobytes of text.
 */
public final class ZipSpike {

  private static final int SLIDES = 100;
  private static final int SLIDE_BYTES = 600_000;
  private static final int BUFFER = 16 * 1024;
  private static final long SEED = 42;

  private ZipSpike() {}

  public static void main(String[] args) throws Exception {
    File dir = Files.createTempDirectory("papyrus-zipspike").toFile();
    File deck = new File(dir, "deck.pptx");
    writeDeck(deck);
    System.out.printf(Locale.ROOT, "deck: %d slides, %d bytes%n", SLIDES, deck.length());
    String expected = digest(deck);
    System.out.println("deck digest: " + expected);

    List<Result> results = new ArrayList<>();
    for (int run = 1; run <= 2; run++) {
      results.add(measure("zipfile", run, deck, dir, "copy-zipfile.pptx", r -> copyWithZipFile(r.in, r.out)));
      results.add(measure("stream", run, deck, dir, "copy-stream.pptx", r -> copyStreaming(r.in, r.out)));
    }
    for (Result result : results) {
      System.out.printf(Locale.ROOT, "%-8s run %d: %6.1f ms, peak heap %4.1f MiB, output %d bytes%n",
          result.strategy, result.run, result.millis, result.peakHeap / (1024.0 * 1024.0),
          result.out.length());
      if (!expected.equals(digest(result.out))) {
        throw new IllegalStateException(result.strategy + " output differs from the input package");
      }
    }
    System.out.println("both outputs digest identically to the input");
  }

  private static void writeDeck(File deck) throws IOException {
    Random random = new Random(SEED);
    byte[] payload = new byte[SLIDE_BYTES];
    try (ZipOutputStream zos = new ZipOutputStream(
        new BufferedOutputStream(Files.newOutputStream(deck.toPath())))) {
      for (int i = 1; i <= SLIDES; i++) {
        zos.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "ppt/slides/slide%d.xml", i)));
        random.nextBytes(payload);
        zos.write(payload);
        zos.closeEntry();
      }
    }
  }

  private static final class Run {
    final File in;
    final File out;

    Run(File in, File out) {
      this.in = in;
      this.out = out;
    }
  }

  private interface Copy {
    void run(Run run) throws IOException;
  }

  private static final class Result {
    final String strategy;
    final int run;
    final double millis;
    final long peakHeap;
    final File out;

    Result(String strategy, int run, double millis, long peakHeap, File out) {
      this.strategy = strategy;
      this.run = run;
      this.millis = millis;
      this.peakHeap = peakHeap;
      this.out = out;
    }
  }

  private static Result measure(String strategy, int run, File deck, File dir, String outName,
      Copy copy) throws IOException {
    File out = new File(dir, outName);
    resetPeaks();
    long start = System.nanoTime();
    copy.run(new Run(deck, out));
    double millis = (System.nanoTime() - start) / 1e6;
    return new Result(strategy, run, millis, peakHeap(), out);
  }

  private static void copyWithZipFile(File in, File out) throws IOException {
    storeCopy(in, out, false);
  }

  private static void copyStreaming(File in, File out) throws IOException {
    storeCopy(in, out, true);
  }

  private static void storeCopy(File in, File out, boolean streaming) throws IOException {
    try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(out.toPath()));
        ZipOutputStream zos = new ZipOutputStream(fileOut)) {
      if (streaming) {
        try (ZipInputStream zis = new ZipInputStream(
            new BufferedInputStream(Files.newInputStream(in.toPath())))) {
          ZipEntry entry;
          while ((entry = zis.getNextEntry()) != null) {
            copyEntry(zis, zos, entry);
          }
        }
      } else {
        try (ZipFile zip = new ZipFile(in)) {
          Enumeration<? extends ZipEntry> entries = zip.entries();
          while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            try (InputStream is = zip.getInputStream(entry)) {
              copyEntry(is, zos, entry);
            }
          }
        }
      }
    }
  }

  private static void copyEntry(InputStream in, ZipOutputStream zos, ZipEntry entry)
      throws IOException {
    ZipEntry copy = new ZipEntry(entry.getName());
    copy.setTime(entry.getTime());
    zos.putNextEntry(copy);
    byte[] buffer = new byte[BUFFER];
    int n;
    while ((n = in.read(buffer)) > 0) {
      zos.write(buffer, 0, n);
    }
    zos.closeEntry();
  }

  private static void resetPeaks() {
    System.gc();
    System.gc();
    for (MemoryPoolMXBean pool : heapPools()) {
      pool.resetPeakUsage();
    }
  }

  private static long peakHeap() {
    long peak = 0;
    for (MemoryPoolMXBean pool : heapPools()) {
      peak += pool.getPeakUsage().getUsed();
    }
    return peak;
  }

  private static List<MemoryPoolMXBean> heapPools() {
    List<MemoryPoolMXBean> pools = new ArrayList<>();
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        pools.add(pool);
      }
    }
    return pools;
  }

  private static String digest(File file) throws Exception {
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    try (ZipFile zip = new ZipFile(file)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        sha.update(entry.getName().getBytes(StandardCharsets.UTF_8));
        try (InputStream is = zip.getInputStream(entry)) {
          byte[] buffer = new byte[BUFFER];
          int n;
          while ((n = is.read(buffer)) > 0) {
            sha.update(buffer, 0, n);
          }
        }
      }
    }
    return HexFormat.of().formatHex(sha.digest());
  }
}
