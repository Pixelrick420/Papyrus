import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Spike S1: a throwaway, POI-free preset geometry evaluator.
 *
 * <p>Reads {@code presetShapeDefinitions.xml}, evaluates every preset at the same three aspect
 * ratios and two adjust modes as the committed POI oracle, and reports the largest deviation.
 * Standalone: not part of the app build.
 */
public final class PrototypeEval {

  private static final int[][] SIZES = {{400, 300}, {400, 225}, {400, 400}};
  private static final String[] MODES = {"default", "extreme"};
  private static final double TOLERANCE = 0.01;
  private static final double FLATNESS = 0.001;

  private PrototypeEval() {}

  public static void main(String[] args) throws Exception {
    File xml = new File(args.length > 0 ? args[0]
        : "app/src/main/assets/ooxml/presetShapeDefinitions.xml");
    File oracleFile = new File(args.length > 1 ? args[1] : "tools/pptx/poi-oracle/oracle.json");

    Map<String, Shape> shapes = parseXml(xml);
    Map<String, Object> oracle = (Map<String, Object>) new Json(
        new String(Files.readAllBytes(oracleFile.toPath()), StandardCharsets.UTF_8)).parse();
    List<Object> oracleCases = (List<Object>) oracle.get("cases");

    Map<String, Map<String, Object>> byKey = new HashMap<>();
    for (Object o : oracleCases) {
      Map<String, Object> c = (Map<String, Object>) o;
      byKey.put(key(str(c.get("shape")), num(c.get("width")), num(c.get("height")),
          str(c.get("adjust"))), c);
    }

    int compared = 0;
    int poiNonFinite = 0;
    int failures = 0;
    double maxDeviation = 0;
    String worst = "";
    for (String name : shapes.keySet()) {
      for (int[] size : SIZES) {
        for (String mode : MODES) {
          String key = key(name, size[0], size[1], mode);
          Map<String, Object> expected = byKey.get(key);
          if (expected == null) {
            System.out.println("missing oracle case: " + key);
            failures++;
            continue;
          }
          if (Boolean.TRUE.equals(expected.get("nonFinite"))) {
            poiNonFinite++;
            continue;
          }
          List<Path2D.Double> actual = shapes.get(name).evaluate(size[0], size[1],
              "extreme".equals(mode));
          if (actual == null) {
            System.out.println("prototype non-finite for finite oracle case: " + key);
            failures++;
            continue;
          }
          compared++;
          double deviation = pathsDeviation(actual, (List<Object>) expected.get("paths"));
          if (deviation > maxDeviation) {
            maxDeviation = deviation;
            worst = key;
          }
          if (deviation > TOLERANCE) {
            System.out.printf(Locale.ROOT, "FAIL %s: %.6f%n", key, deviation);
            failures++;
          }
        }
      }
    }

    System.out.printf(Locale.ROOT, "cases=%d compared=%d poiNonFiniteSkipped=%d "
            + "maxDeviation=%.6f tolerance=%.2f failures=%d%n",
        shapes.size() * SIZES.length * MODES.length, compared, poiNonFinite,
        maxDeviation, TOLERANCE, failures);
    System.out.println("worst: " + (worst.isEmpty() ? "-" : worst));
    if (failures > 0 || maxDeviation > TOLERANCE) {
      System.exit(1);
    }
  }

  private static String key(String shape, Number w, Number h, String adjust) {
    return shape + "|" + w.intValue() + "x" + h.intValue() + "|" + adjust;
  }

  private static String key(String shape, int w, int h, String adjust) {
    return shape + "|" + w + "x" + h + "|" + adjust;
  }

  // ---------------------------------------------------------------- comparison

  private static double pathsDeviation(List<Path2D.Double> actual, List<Object> expectedPaths) {
    if (actual.size() != expectedPaths.size()) {
      return Double.POSITIVE_INFINITY;
    }
    double max = 0;
    for (int i = 0; i < actual.size(); i++) {
      List<SubPath> actualSubs = subpaths(actual.get(i));
      List<Object> expectedSubs = (List<Object>) expectedPaths.get(i);
      if (actualSubs.size() != expectedSubs.size()) {
        return Double.POSITIVE_INFINITY;
      }
      for (int j = 0; j < actualSubs.size(); j++) {
        Map<String, Object> expectedSub = (Map<String, Object>) expectedSubs.get(j);
        Path2D.Double expectedPath = new Path2D.Double();
        appendSegments(expectedPath, (List<Object>) expectedSub.get("segments"));
        List<SubPath> expectedParsed = subpaths(expectedPath);
        if (expectedParsed.size() != 1) {
          return Double.POSITIVE_INFINITY;
        }
        double d = hausdorff(actualSubs.get(j), expectedParsed.get(0));
        max = Math.max(max, d);
      }
    }
    return max;
  }

  private static double hausdorff(SubPath a, SubPath b) {
    if (a.pts.isEmpty() || b.pts.isEmpty()) {
      return a.pts.isEmpty() && b.pts.isEmpty() ? 0 : Double.POSITIVE_INFINITY;
    }
    Polyline pa = new Polyline(a);
    Polyline pb = new Polyline(b);
    double max = 0;
    for (double[] p : a.pts) {
      max = Math.max(max, pb.distance(p[0], p[1]));
    }
    for (double[] p : b.pts) {
      max = Math.max(max, pa.distance(p[0], p[1]));
    }
    return max;
  }

  private static final class SubPath {
    final List<double[]> pts = new ArrayList<>();
    boolean closed;
  }

  /** Exact point-to-segment distance over a flattened subpath, indexed on a uniform grid. */
  private static final class Polyline {
    private static final double CELL = 8;
    private final List<double[]> pts;
    private final int segs;
    private final Map<Long, List<Integer>> grid = new HashMap<>();

    Polyline(SubPath sub) {
      pts = sub.pts;
      segs = pts.size() < 2 ? 0 : sub.closed ? pts.size() : pts.size() - 1;
      double[] a = new double[2];
      double[] b = new double[2];
      for (int i = 0; i < segs; i++) {
        a = pts.get(i);
        b = pts.get((i + 1) % pts.size());
        int x0 = cell(Math.min(a[0], b[0]));
        int x1 = cell(Math.max(a[0], b[0]));
        int y0 = cell(Math.min(a[1], b[1]));
        int y1 = cell(Math.max(a[1], b[1]));
        for (int x = x0; x <= x1; x++) {
          for (int y = y0; y <= y1; y++) {
            grid.computeIfAbsent(key(x, y), k -> new ArrayList<>()).add(i);
          }
        }
      }
    }

    double distance(double x, double y) {
      if (segs == 0) {
        double best = Double.POSITIVE_INFINITY;
        for (double[] p : pts) {
          best = Math.min(best, Math.hypot(x - p[0], y - p[1]));
        }
        return best;
      }
      int cx = cell(x);
      int cy = cell(y);
      double best = Double.POSITIVE_INFINITY;
      for (int radius = 0; radius <= 64; radius++) {
        for (int dx = -radius; dx <= radius; dx++) {
          for (int dy = -radius; dy <= radius; dy++) {
            if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dy)) != radius) {
              continue;
            }
            List<Integer> cell = grid.get(key(cx + dx, cy + dy));
            if (cell == null) {
              continue;
            }
            for (int seg : cell) {
              best = Math.min(best, pointToSegment(x, y, pts.get(seg),
                  pts.get((seg + 1) % pts.size())));
            }
          }
        }
        if (best <= radius * CELL) {
          return best;
        }
      }
      throw new IllegalStateException("grid search too shallow at " + x + "," + y);
    }

    private static double pointToSegment(double x, double y, double[] a, double[] b) {
      double dx = b[0] - a[0];
      double dy = b[1] - a[1];
      double len2 = dx * dx + dy * dy;
      double t = len2 == 0 ? 0 : ((x - a[0]) * dx + (y - a[1]) * dy) / len2;
      t = Math.max(0, Math.min(1, t));
      return Math.hypot(x - (a[0] + dx * t), y - (a[1] + dy * t));
    }

    private static int cell(double v) {
      return (int) Math.floor(v / CELL);
    }

    private static long key(int x, int y) {
      return ((long) x << 32) ^ (y & 0xffffffffL);
    }
  }

  private static List<SubPath> subpaths(Path2D.Double path) {
    List<SubPath> subs = new ArrayList<>();
    SubPath current = null;
    double startX = 0;
    double startY = 0;
    FlatteningPathIterator it =
        new FlatteningPathIterator(path.getPathIterator(null), FLATNESS);
    double[] c = new double[6];
    while (!it.isDone()) {
      int type = it.currentSegment(c);
      if (type == PathIterator.SEG_MOVETO) {
        current = new SubPath();
        subs.add(current);
        current.pts.add(new double[] {c[0], c[1]});
        startX = c[0];
        startY = c[1];
      } else if (type == PathIterator.SEG_LINETO) {
        if (current == null) {
          current = new SubPath();
          subs.add(current);
          current.pts.add(new double[] {startX, startY});
        }
        current.pts.add(new double[] {c[0], c[1]});
      } else if (type == PathIterator.SEG_CLOSE) {
        if (current != null) {
          current.closed = true;
        }
        current = null;
      }
      it.next();
    }
    return subs;
  }

  private static void appendSegments(Path2D.Double path, List<Object> segments) {
    for (Object o : segments) {
      List<Object> s = (List<Object>) o;
      String op = str(s.get(0));
      switch (op) {
        case "M" -> path.moveTo(dbl(s.get(1)), dbl(s.get(2)));
        case "L" -> path.lineTo(dbl(s.get(1)), dbl(s.get(2)));
        case "Q" -> path.quadTo(dbl(s.get(1)), dbl(s.get(2)), dbl(s.get(3)), dbl(s.get(4)));
        case "C" -> path.curveTo(dbl(s.get(1)), dbl(s.get(2)), dbl(s.get(3)), dbl(s.get(4)),
            dbl(s.get(5)), dbl(s.get(6)));
        case "Z" -> path.closePath();
        default -> throw new IllegalArgumentException(op);
      }
    }
  }

  // ---------------------------------------------------------------- XML model

  private static Map<String, Shape> parseXml(File file) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    Document doc = factory.newDocumentBuilder().parse(file);
    Map<String, Shape> shapes = new LinkedHashMap<>();
    for (Element shape : children(doc.getDocumentElement())) {
      shapes.put(shape.getTagName(), new Shape(shape));
    }
    return shapes;
  }

  private static List<Element> children(Element parent) {
    List<Element> out = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node n = nodes.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        out.add((Element) n);
      }
    }
    return out;
  }

  private static Element child(Element parent, String name) {
    for (Element e : children(parent)) {
      if (e.getTagName().equals(name)) {
        return e;
      }
    }
    return null;
  }

  private static final class Shape {
    final List<String[]> adjusts = new ArrayList<>();
    final List<String[]> guides = new ArrayList<>();
    final List<List<Command>> paths = new ArrayList<>();

    Shape(Element e) {
      Element avLst = child(e, "avLst");
      if (avLst != null) {
        for (Element gd : children(avLst)) {
          adjusts.add(new String[] {gd.getAttribute("name"), gd.getAttribute("fmla")});
        }
      }
      Element gdLst = child(e, "gdLst");
      if (gdLst != null) {
        for (Element gd : children(gdLst)) {
          guides.add(new String[] {gd.getAttribute("name"), gd.getAttribute("fmla")});
        }
      }
      Element pathLst = child(e, "pathLst");
      if (pathLst != null) {
        for (Element path : children(pathLst)) {
          List<Command> commands = new ArrayList<>();
          for (Element cmd : children(path)) {
            commands.add(new Command(cmd.getTagName(), cmd));
          }
          paths.add(commands);
        }
      }
    }

    /** Null when the outline is not finite (POI produces NaN for zero-radius arcs). */
    List<Path2D.Double> evaluate(double w, double h, boolean extreme) {
      Ctx defaults = new Ctx(w, h);
      for (String[] adj : adjusts) {
        defaults.put(adj[0], defaults.eval(adj[1]));
      }
      Ctx ctx = new Ctx(w, h);
      for (String[] adj : adjusts) {
        double value = defaults.get(adj[0]);
        ctx.put(adj[0], extreme ? (value == 0 ? 0 : value * 2) : value);
      }
      for (String[] gd : guides) {
        ctx.put(gd[0], ctx.eval(gd[1]));
      }

      List<Path2D.Double> result = new ArrayList<>();
      for (List<Command> commands : paths) {
        Path2D.Double path = new Path2D.Double();
        for (Command command : commands) {
          command.apply(path, ctx);
        }
        result.add(path);
      }
      for (Path2D.Double p : result) {
        if (!isFinite(p)) {
          return null;
        }
      }
      return result;
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
  }

  private static final class Command {
    final String op;
    final String[] args;

    Command(String op, Element e) {
      this.op = op;
      if (op.equals("arcTo")) {
        args = new String[] {e.getAttribute("wR"), e.getAttribute("hR"),
            e.getAttribute("stAng"), e.getAttribute("swAng")};
      } else {
        List<String> values = new ArrayList<>();
        for (Element pt : children(e)) {
          values.add(pt.getAttribute("x"));
          values.add(pt.getAttribute("y"));
        }
        args = values.toArray(new String[0]);
      }
    }

    void apply(Path2D.Double path, Ctx ctx) {
      switch (op) {
        case "moveTo" -> path.moveTo(ctx.eval(args[0]), ctx.eval(args[1]));
        case "lnTo" -> path.lineTo(ctx.eval(args[0]), ctx.eval(args[1]));
        case "quadBezTo" -> path.quadTo(ctx.eval(args[0]), ctx.eval(args[1]), ctx.eval(args[2]),
            ctx.eval(args[3]));
        case "cubicBezTo" -> path.curveTo(ctx.eval(args[0]), ctx.eval(args[1]), ctx.eval(args[2]),
            ctx.eval(args[3]), ctx.eval(args[4]), ctx.eval(args[5]));
        case "arcTo" -> arcTo(path, ctx.eval(args[0]), ctx.eval(args[1]),
            ctx.eval(args[2]) / 60000.0, ctx.eval(args[3]) / 60000.0);
        case "close" -> path.closePath();
        default -> throw new IllegalArgumentException(op);
      }
    }

    /**
     * DrawingML arcTo. POI draws arcs by appending an AWT {@code Arc2D}, so matching POI means
     * reproducing AWT's ArcIterator arithmetic here, without AWT.
     */
    private static void arcTo(Path2D.Double path, double wr, double hr, double startDeg,
        double sweepDeg) {
      Point2D current = path.getCurrentPoint();
      double angSt = convertOoxml2AwtAngle(startDeg, wr, hr);
      double angExt = convertOoxml2AwtAngle(startDeg + sweepDeg, wr, hr) - angSt;
      double rad = Math.toRadians(startDeg);
      double theta = Math.atan2(wr * Math.sin(rad), hr * Math.cos(rad));
      double cx = current.getX() - wr * Math.cos(theta);
      double cy = current.getY() - hr * Math.sin(theta);
      if (wr < 0 || hr < 0) {
        return;
      }
      double angle = -Math.toRadians(angSt);
      double extent = -angExt;
      int arcSegs;
      double increment;
      double cv;
      if (extent >= 360 || extent <= -360) {
        arcSegs = 4;
        increment = Math.PI / 2;
        cv = 0.5522847498307933;
        if (extent < 0) {
          increment = -increment;
          cv = -cv;
        }
      } else {
        arcSegs = (int) Math.ceil(Math.abs(extent) / 90.0);
        increment = Math.toRadians(extent / arcSegs);
        cv = 4.0 / 3.0 * Math.sin(increment / 2) / (1 + Math.cos(increment / 2));
        if (cv == 0) {
          arcSegs = 0;
        }
      }
      path.lineTo(cx + Math.cos(angle) * wr, cy + Math.sin(angle) * hr);
      for (int i = 1; i <= arcSegs; i++) {
        double a = angle + increment * (i - 1);
        double relx = Math.cos(a);
        double rely = Math.sin(a);
        double c1x = cx + (relx - cv * rely) * wr;
        double c1y = cy + (rely + cv * relx) * hr;
        a += increment;
        relx = Math.cos(a);
        rely = Math.sin(a);
        double c2x = cx + (relx + cv * rely) * wr;
        double c2y = cy + (rely - cv * relx) * hr;
        path.curveTo(c1x, c1y, c2x, c2y, cx + relx * wr, cy + rely * hr);
      }
    }

    /** POI's arc angle conversion: a geometric angle becomes the arc's elliptic parameter. */
    private static double convertOoxml2AwtAngle(double start, double wr, double hr) {
      double ratio = hr / wr;
      double s = -start;
      double rem = s % 360.0;
      double base = s - rem;
      switch ((int) (rem / 90.0)) {
        case -3 -> {
          base -= 360;
          rem += 360;
        }
        case -2, -1 -> {
          base -= 180;
          rem += 180;
        }
        case 0 -> {
        }
        case 1, 2 -> {
          base += 180;
          rem -= 180;
        }
        case 3 -> {
          base += 360;
          rem -= 360;
        }
        default -> {
        }
      }
      return base + Math.toDegrees(Math.atan2(Math.tan(Math.toRadians(rem)), ratio));
    }
  }

  private static final class Ctx {
    private final Map<String, Double> values = new HashMap<>();

    Ctx(double w, double h) {
      double ss = Math.min(w, h);
      values.put("w", w);
      values.put("h", h);
      values.put("ss", ss);
      values.put("ls", Math.max(w, h));
      values.put("t", 0.0);
      values.put("l", 0.0);
      values.put("r", w);
      values.put("b", h);
      values.put("hc", w / 2);
      values.put("vc", h / 2);
      values.put("wd2", w / 2);
      values.put("wd3", w / 3);
      values.put("wd4", w / 4);
      values.put("wd5", w / 5);
      values.put("wd6", w / 6);
      values.put("wd8", w / 8);
      values.put("wd10", w / 10);
      values.put("wd32", w / 32);
      values.put("hd2", h / 2);
      values.put("hd3", h / 3);
      values.put("hd4", h / 4);
      values.put("hd5", h / 5);
      values.put("hd6", h / 6);
      values.put("hd8", h / 8);
      values.put("ssd2", ss / 2);
      values.put("ssd4", ss / 4);
      values.put("ssd6", ss / 6);
      values.put("ssd8", ss / 8);
      values.put("ssd16", ss / 16);
      values.put("ssd32", ss / 32);
      values.put("cd2", 10800000.0);
      values.put("cd4", 5400000.0);
      values.put("cd8", 2700000.0);
      values.put("3cd4", 16200000.0);
      values.put("3cd8", 8100000.0);
      values.put("5cd8", 13500000.0);
      values.put("7cd8", 18900000.0);
    }

    void put(String name, double value) {
      values.put(name, value);
    }

    double get(String name) {
      Double v = values.get(name);
      if (v != null) {
        return v;
      }
      try {
        return Double.parseDouble(name);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("unknown guide: " + name);
      }
    }

    double eval(String fmla) {
      String[] t = fmla.trim().split("\\s+");
      switch (t[0]) {
        case "val":
          return get(t[1]);
        case "*/": {
          double c = get(t[3]);
          return c == 0 ? 0 : get(t[1]) * get(t[2]) / c;
        }
        case "+-":
          return get(t[1]) + get(t[2]) - get(t[3]);
        case "+/": {
          double c = get(t[3]);
          return c == 0 ? 0 : (get(t[1]) + get(t[2])) / c;
        }
        case "?:":
          return get(t[1]) > 0 ? get(t[2]) : get(t[3]);
        case "abs":
          return Math.abs(get(t[1]));
        case "at2":
          return Math.toDegrees(Math.atan2(get(t[2]), get(t[1]))) * 60000;
        case "cat2":
          return get(t[1]) * Math.cos(Math.atan2(get(t[3]), get(t[2])));
        case "cos":
          return get(t[1]) * Math.cos(Math.toRadians(get(t[2]) / 60000));
        case "max":
          return Math.max(get(t[1]), get(t[2]));
        case "min":
          return Math.min(get(t[1]), get(t[2]));
        case "mod":
          return Math.sqrt(get(t[1]) * get(t[1]) + get(t[2]) * get(t[2])
              + get(t[3]) * get(t[3]));
        case "pin":
          return Math.max(get(t[1]), Math.min(get(t[2]), get(t[3])));
        case "sat2":
          return get(t[1]) * Math.sin(Math.atan2(get(t[3]), get(t[2])));
        case "sin":
          return get(t[1]) * Math.sin(Math.toRadians(get(t[2]) / 60000));
        case "sqrt":
          return Math.sqrt(get(t[1]));
        case "tan":
          return get(t[1]) * Math.tan(Math.toRadians(get(t[2]) / 60000));
        default:
          return get(t[0]);
      }
    }
  }

  // ---------------------------------------------------------------- JSON

  private static String str(Object o) {
    return (String) o;
  }

  private static double num(Object o) {
    return ((Number) o).doubleValue();
  }

  private static double dbl(Object o) {
    return ((Number) o).doubleValue();
  }

  /** Just enough JSON for the oracle file. */
  private static final class Json {
    private final String s;
    private int i;

    Json(String s) {
      this.s = s;
    }

    Object parse() {
      Object v = value();
      skip();
      if (i != s.length()) {
        throw new IllegalArgumentException("trailing input at " + i);
      }
      return v;
    }

    private Object value() {
      skip();
      char c = s.charAt(i);
      if (c == '{') {
        i++;
        Map<String, Object> map = new LinkedHashMap<>();
        skip();
        if (s.charAt(i) == '}') {
          i++;
          return map;
        }
        while (true) {
          skip();
          String k = string();
          skip();
          expect(':');
          map.put(k, value());
          skip();
          if (s.charAt(i) == ',') {
            i++;
          } else {
            expect('}');
            return map;
          }
        }
      }
      if (c == '[') {
        i++;
        List<Object> list = new ArrayList<>();
        skip();
        if (s.charAt(i) == ']') {
          i++;
          return list;
        }
        while (true) {
          list.add(value());
          skip();
          if (s.charAt(i) == ',') {
            i++;
          } else {
            expect(']');
            return list;
          }
        }
      }
      if (c == '"') {
        return string();
      }
      if (c == 't') {
        i += 4;
        return Boolean.TRUE;
      }
      if (c == 'f') {
        i += 5;
        return Boolean.FALSE;
      }
      if (c == 'n') {
        i += 4;
        return null;
      }
      int start = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
        i++;
      }
      return Double.parseDouble(s.substring(start, i));
    }

    private String string() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        char c = s.charAt(i++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\') {
          char e = s.charAt(i++);
          switch (e) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'u' -> {
              sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
              i += 4;
            }
            default -> sb.append(e);
          }
        } else {
          sb.append(c);
        }
      }
    }

    private void expect(char c) {
      skip();
      if (s.charAt(i) != c) {
        throw new IllegalArgumentException("expected " + c + " at " + i + ", got " + s.charAt(i));
      }
      i++;
    }

    private void skip() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }
  }
}
