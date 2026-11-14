import org.apache.poi.sl.draw.geom.Context;
import org.apache.poi.sl.draw.geom.CustomGeometry;
import org.apache.poi.sl.draw.geom.Guide;
import org.apache.poi.sl.draw.geom.IAdjustableShape;
import org.apache.poi.sl.draw.geom.Path;
import org.apache.poi.sl.draw.geom.PathIf;
import org.apache.poi.sl.draw.geom.PresetGeometries;

import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Dumps every POI preset geometry as an exact outline, one case per preset, aspect ratio and
 * adjust mode.
 *
 * <p>The outline is POI's own path (arcs already flattened into cubics by {@code Arc2D}), not a
 * resampled polyline: the committed file stays small and the comparison can sample it as finely
 * as it likes. Standalone: not part of the app build. Run through {@code run.sh}.
 */
public final class OracleDump {

  private static final String POI_VERSION = "5.2.5";
  private static final int[][] SIZES = {{400, 300}, {400, 225}, {400, 400}};
  private static final String[] MODES = {"default", "extreme"};

  private OracleDump() {}

  public static void main(String[] args) throws IOException {
    File out = new File(args.length > 0 ? args[0] : "oracle.json");
    PresetGeometries presets = PresetGeometries.getInstance();
    List<String> names = new ArrayList<>(presets.keySet());
    Collections.sort(names);

    StringBuilder sb = new StringBuilder(1 << 20);
    sb.append("{\n");
    sb.append("  \"formatVersion\": 1,\n");
    sb.append("  \"generator\": \"org.apache.poi:poi:").append(POI_VERSION).append("\",\n");
    sb.append("  \"geometrySource\": \"poi-").append(POI_VERSION)
        .append(" presetShapeDefinitions.xml\",\n");
    sb.append("  \"tolerance\": 0.01,\n");
    sb.append("  \"cases\": [");

    boolean firstCase = true;
    int nonFinite = 0;
    for (String name : names) {
      CustomGeometry geom = presets.get(name);
      if (geom == null) {
        continue;
      }
      for (int[] size : SIZES) {
        Rectangle2D anchor = new Rectangle2D.Double(0, 0, size[0], size[1]);
        for (String mode : MODES) {
          IAdjustableShape adjust = "extreme".equals(mode) ? extreme(geom, anchor) : n -> null;
          Context ctx = new Context(geom, anchor, adjust);
          List<Path2D.Double> evaluated = new ArrayList<>();
          boolean finite = true;
          for (PathIf p : geom) {
            Path2D.Double path = ((Path) p).getPath(ctx);
            finite &= isFinite(path);
            evaluated.add(path);
          }
          StringBuilder paths = new StringBuilder();
          if (finite) {
            for (int i = 0; i < evaluated.size(); i++) {
              if (i > 0) {
                paths.append(", ");
              }
              appendPath(paths, evaluated.get(i));
            }
          } else {
            nonFinite++;
          }
          if (!firstCase) {
            sb.append(',');
          }
          firstCase = false;
          sb.append("\n    {\"shape\": \"").append(name)
              .append("\", \"width\": ").append(size[0])
              .append(", \"height\": ").append(size[1])
              .append(", \"adjust\": \"").append(mode).append('"');
          if (finite) {
            sb.append(", \"paths\": [").append(paths).append(']');
          } else {
            sb.append(", \"nonFinite\": true");
          }
          sb.append('}');
        }
      }
    }
    sb.append("\n  ]\n}\n");

    try (Writer w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
      w.write(sb.toString());
    }
    System.err.println("wrote " + (names.size() * SIZES.length * MODES.length) + " cases ("
        + names.size() + " presets, " + nonFinite + " non-finite) to " + out + ", "
        + out.length() + " bytes");
  }

  /** Every adjust value doubled; zero stays zero. */
  private static IAdjustableShape extreme(CustomGeometry geom, Rectangle2D anchor) {
    Context defaults = new Context(geom, anchor, n -> null);
    return name -> {
      Guide guide = new Guide();
      guide.setName(name);
      guide.setFmla("val " + num(2 * defaults.getValue(name)));
      return guide;
    };
  }

  private static void appendPath(StringBuilder sb, Path2D.Double path) {
    sb.append('[');
    boolean open = false;
    boolean firstSub = true;
    double startX = 0;
    double startY = 0;
    double[] c = new double[6];
    for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
      int type = it.currentSegment(c);
      if (type == PathIterator.SEG_MOVETO) {
        if (open) {
          sb.append("]}");
          open = false;
        }
        if (!firstSub) {
          sb.append(", ");
        }
        firstSub = false;
        open = true;
        startX = c[0];
        startY = c[1];
        sb.append("{\"segments\": [[\"M\",").append(num(c[0])).append(',').append(num(c[1]))
            .append(']');
      } else if (type == PathIterator.SEG_CLOSE) {
        sb.append(", [\"Z\"]").append("]}");
        open = false;
      } else {
        if (!open) {
          if (!firstSub) {
            sb.append(", ");
          }
          firstSub = false;
          sb.append("{\"segments\": [[\"M\",").append(num(startX)).append(',')
              .append(num(startY)).append(']');
          open = true;
        }
        if (type == PathIterator.SEG_LINETO) {
          sb.append(", [\"L\",").append(num(c[0])).append(',').append(num(c[1])).append(']');
        } else if (type == PathIterator.SEG_QUADTO) {
          sb.append(", [\"Q\",").append(num(c[0])).append(',').append(num(c[1])).append(',')
              .append(num(c[2])).append(',').append(num(c[3])).append(']');
        } else if (type == PathIterator.SEG_CUBICTO) {
          sb.append(", [\"C\",").append(num(c[0])).append(',').append(num(c[1])).append(',')
              .append(num(c[2])).append(',').append(num(c[3])).append(',')
              .append(num(c[4])).append(',').append(num(c[5])).append(']');
        }
      }
    }
    if (open) {
      sb.append(']').append('}');
    }
    sb.append(']');
  }

  private static boolean isFinite(Path2D.Double path) {
    double[] c = new double[6];
    for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
      int n = it.currentSegment(c);
      int limit = n == PathIterator.SEG_CUBICTO ? 6 : n == PathIterator.SEG_QUADTO ? 4 : 2;
      for (int i = 0; i < limit; i++) {
        if (!Double.isFinite(c[i])) {
          return false;
        }
      }
    }
    return true;
  }

  private static String num(double v) {
    if (v == Math.rint(v) && Math.abs(v) < 1e15) {
      return Long.toString((long) v);
    }
    String s = String.format(Locale.ROOT, "%.4f", v);
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == '0') {
      end--;
    }
    if (end > 0 && s.charAt(end - 1) == '.') {
      end--;
    }
    return s.substring(0, end);
  }
}
