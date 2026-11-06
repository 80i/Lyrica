package net.lyrica.v1_21_10.words;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Reads timed lyrics in TTML: a {@code p} per line, a timed {@code span} per word, syllable or character, what is sung
 * in the background under {@code ttm:role="x-bg"}, and who sings under {@code ttm:agent}. Translations and romanized
 * rows are left out. The files come from anywhere, so a file with a document type is refused, nothing outside the file
 * is ever read, and a bad file gives no lines rather than an exception. A bad line is only that line lost.
 *
 * <p>Names are matched without their prefixes and with the parser's namespaces off, so that a file which forgot to
 * declare {@code ttm:} still reads.
 */
public final class TtmlReader {

  private static final long LONGEST_TIME_MS = 86_400_000L;
  private static final long LONGEST_UNTIMED_LINE_MS = 12_000L;
  private static final long LAST_UNTIMED_LINE_MS = 6_000L;
  private static final double DEFAULT_FRAME_RATE = 30.0;
  private static final double DEFAULT_TICK_RATE = 1.0;
  private static final int DEEPEST = 64;

  private static final ErrorHandler QUIET = new ErrorHandler() {
    @Override
    public void warning(SAXParseException exception) {
    }

    @Override
    public void error(SAXParseException exception) {
    }

    @Override
    public void fatalError(SAXParseException exception) throws SAXParseException {
      throw exception;
    }
  };

  private final Map<String, Integer> voices = new HashMap<>();
  private final List<Pending> lines = new ArrayList<>();
  private double frameRate = DEFAULT_FRAME_RATE;
  private double tickRate = DEFAULT_TICK_RATE;
  private int nextVoice = 1;

  private TtmlReader() {
  }

  public static TimedLyrics parse(String ttml) {
    return parse(ttml, 0L);
  }

  /** {@code durationMs} (0 when unknown) keeps the last line inside the song. */
  public static TimedLyrics parse(String ttml, long durationMs) {
    if (ttml == null || ttml.isBlank()) {
      return new TimedLyrics(List.of());
    }

    try {
      Document document = safeBuilder().parse(new InputSource(new StringReader(ttml)));
      TtmlReader reader = new TtmlReader();
      reader.read(document.getDocumentElement());
      return new TimedLyrics(reader.finish(durationMs));
    } catch (ParserConfigurationException | SAXException | IOException | RuntimeException | StackOverflowError failure) {
      // The overflow is a file nested beyond reason. On a worker it would otherwise leave the import unfinished for good.
      return new TimedLyrics(List.of());
    }
  }

  private static DocumentBuilder safeBuilder() throws ParserConfigurationException {
    // The JDK's own parser by name: whatever else the game has on its class path may not know these switches.
    DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setNamespaceAware(false);
    factory.setValidating(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    builder.setErrorHandler(QUIET);
    builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
    return builder;
  }

  // The file

  private void read(Element root) {
    this.frameRate = positiveOr(attribute(root, "frameRate"), DEFAULT_FRAME_RATE);
    this.tickRate = positiveOr(attribute(root, "tickRate"), DEFAULT_TICK_RATE);
    this.readAgents(root, 0);
    this.readContainer(root, 0L, 0);
  }

  private void readAgents(Element parent, int depth) {
    if (depth > DEEPEST) {
      return;
    }

    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element element)) {
        continue;
      }

      String name = localName(element.getNodeName());
      if ("agent".equals(name)) {
        String id = attribute(element, "id");
        if (id != null && !this.voices.containsKey(id)) {
          // A group is everybody at once, which belongs to neither side of a duet.
          boolean singer = !"group".equals(attribute(element, "type")) && !"other".equals(attribute(element, "type"));
          this.voices.put(id, singer ? this.nextVoice++ : 0);
        }
      } else if ("head".equals(name) || "metadata".equals(name)) {
        this.readAgents(element, depth + 1);
      }
    }
  }

  /**
   * The standard has a child's times count from its parent's start, while most lyrics files write every time from the
   * song's start with the same number on the parent. So a parent's start only counts if its first line would otherwise
   * start before it.
   */
  private void readContainer(Element container, long offset, int depth) {
    if (depth > DEEPEST) {
      return;
    }

    long own = offset;
    long begin = this.time(container, "begin");
    if (begin > 0L) {
      long first = this.firstLineBegin(container, 0);
      if (first >= 0L && first < begin) {
        own += begin;
      }
    }

    for (Node node = container.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element element)) {
        continue;
      }

      String name = localName(element.getNodeName());
      if ("p".equals(name)) {
        this.readLine(element, own);
      } else if (!"head".equals(name) && !"metadata".equals(name) && !"span".equals(name)) {
        this.readContainer(element, own, depth + 1);
      }
    }
  }

  private long firstLineBegin(Element container, int depth) {
    if (depth > DEEPEST) {
      return -1L;
    }

    for (Node node = container.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element) {
        boolean line = "p".equals(localName(element.getNodeName()));
        long found = line ? this.time(element, "begin") : this.firstLineBegin(element, depth + 1);
        if (found >= 0L) {
          return found;
        }
      }
    }

    return -1L;
  }

  // One line

  private void readLine(Element p, long offset) {
    String role = attribute(p, "role");
    boolean behind = isBackground(role);
    if (role != null && role.startsWith("x-") && !behind) {
      return;
    }

    long begin = this.time(p, "begin");
    long end = this.endOf(p, begin);
    Stretches main = new Stretches();
    Stretches back = new Stretches();
    this.collect(p, behind ? back : main, back, 0);

    TimedLine backLine = build(back, behind ? begin : -1L, begin, end, offset);
    TimedLine line = behind ? null : build(main, begin, begin, end, offset);
    if (line == null && backLine == null) {
      return;
    }

    // What has nothing but a background part is a line in its own right, until it finds a line to go with.
    Pending pending = new Pending();
    pending.line = line == null ? backLine : line;
    pending.backOnly = line == null;
    pending.background = line == null ? null : backLine;
    pending.agent = attribute(p, "agent");
    if (pending.agent == null) {
      pending.agent = this.firstSpanAgent(p, 0);
    }

    pending.endKnown = pending.line.endMs() > pending.line.startMs();
    this.lines.add(pending);
  }

  private String firstSpanAgent(Element parent, int depth) {
    if (depth > DEEPEST) {
      return null;
    }

    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && "span".equals(localName(element.getNodeName()))) {
        String agent = attribute(element, "agent");
        agent = agent == null ? this.firstSpanAgent(element, depth + 1) : agent;
        if (agent != null) {
          return agent;
        }
      }
    }

    return null;
  }

  private void collect(Element parent, Stretches into, Stretches back, int depth) {
    if (depth > DEEPEST) {
      return;
    }

    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      short type = node.getNodeType();
      if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
        into.text(node.getNodeValue());
      } else if (node instanceof Element element) {
        String name = localName(element.getNodeName());
        if ("br".equals(name)) {
          into.text(" ");
        } else if ("span".equals(name)) {
          this.collectSpan(element, into, back, depth);
        }
      }
    }
  }

  private void collectSpan(Element span, Stretches into, Stretches back, int depth) {
    String role = attribute(span, "role");
    Stretches target = into;
    if (isBackground(role)) {
      target = back;
      target.text(" ");
    } else if (role != null && role.startsWith("x-")) {
      return;
    }

    long begin = this.time(span, "begin");
    if (begin >= 0L) {
      target.open(begin, this.endOf(span, begin));
    }

    this.collect(span, target, back, depth + 1);
    if (target != into) {
      into.text(" ");
    }
  }

  /**
   * Turns the stretches of a line into a line, or null when they say nothing that could be shown. The end is -1 when
   * nothing says it.
   *
   * @param ownBegin where the line says it begins, -1 for a part behind a line, which begins with its first word
   */
  private static TimedLine build(Stretches stretches, long ownBegin, long lineBegin, long lineEnd, long offset) {
    stretches.tidy(lineBegin, lineEnd);
    String text = stretches.textOut.toString();
    if (text.isEmpty() || LrcReader.isOnlyMusicMarks(text)) {
      return null;
    }

    long firstTime = stretches.firstTime();
    long start = ownBegin;
    if (firstTime >= 0L) {
      start = start < 0L ? firstTime : Math.min(start, firstTime);
    }

    start = start < 0L ? lineBegin : start;
    if (start < 0L) {
      return null;
    }

    SungParts parts = stretches.parts(start);
    long end = parts != null ? start + parts.lastEnd() : lineEnd;
    return new TimedLine(start + offset, end < 0L ? -1L : end + offset, text, parts, 0, null);
  }

  // The whole song

  private List<TimedLine> finish(long durationMs) {
    this.lines.sort((a, b) -> Long.compare(a.line.startMs(), b.line.startMs()));
    this.joinBackOnlyLines();

    int[] voiceLines = new int[this.nextVoiceAfterAll()];
    for (int i = 0; i < this.lines.size(); i++) {
      voiceLines[this.voiceOf(this.lines.get(i).agent)]++;
    }

    int singers = 0;
    for (int i = 1; i < voiceLines.length; i++) {
      if (voiceLines[i] > 0) {
        singers++;
      }
    }

    List<TimedLine> finished = new ArrayList<>(this.lines.size());
    for (int i = 0; i < this.lines.size(); i++) {
      Pending pending = this.lines.get(i);
      TimedLine line = pending.line;
      TimedLine behind = pending.background == null ? null : closed(pending.background, durationMs);
      long next = i + 1 < this.lines.size() ? this.lines.get(i + 1).line.startMs() : Long.MAX_VALUE;
      int voice = singers > 1 ? this.voiceOf(pending.agent) : 0;
      long sungEnd = line.endMs();
      long end;
      if (line.parts() == null && !pending.endKnown) {
        end = next == Long.MAX_VALUE
            ? line.startMs() + LAST_UNTIMED_LINE_MS
            : Math.min(next, line.startMs() + LONGEST_UNTIMED_LINE_MS);
      } else if (line.parts() == null) {
        end = sungEnd;
      } else {
        long last = behind == null ? sungEnd : Math.max(sungEnd, behind.sungEndMs());
        end = Math.max(last, Math.min(next, last + TimedLine.LINGER_MS));
      }

      if (durationMs > line.startMs()) {
        end = Math.min(end, durationMs);
      }

      finished.add(new TimedLine(line.startMs(), Math.max(line.startMs() + 1L, end), line.text(), line.parts(), voice,
          behind));
    }

    return finished;
  }

  private static TimedLine closed(TimedLine behind, long durationMs) {
    long end = behind.endMs() >= 0L ? behind.endMs() : behind.startMs() + LAST_UNTIMED_LINE_MS;
    if (durationMs > behind.startMs()) {
      end = Math.min(end, durationMs);
    }

    return new TimedLine(behind.startMs(), Math.max(behind.startMs() + 1L, end), behind.text(), behind.parts());
  }

  /** A background part written as its own {@code p} joins the line it is sung under, when there is one. */
  private void joinBackOnlyLines() {
    for (int i = this.lines.size() - 1; i > 0; i--) {
      Pending pending = this.lines.get(i);
      Pending before = this.lines.get(i - 1);
      boolean under = pending.backOnly
          && !before.backOnly
          && before.background == null
          && pending.line.startMs() < endOrStart(before.line);
      if (under) {
        before.background = pending.line;
        this.lines.remove(i);
      }
    }
  }

  private static long endOrStart(TimedLine line) {
    return line.endMs() >= 0L ? line.endMs() : line.startMs();
  }

  private int nextVoiceAfterAll() {
    for (int i = 0; i < this.lines.size(); i++) {
      this.voiceOf(this.lines.get(i).agent);
    }

    return this.nextVoice;
  }

  /** A line can name several singers, which is everybody at once. One the head never listed takes the next number. */
  private int voiceOf(String agent) {
    if (agent == null || agent.isBlank() || agent.strip().indexOf(' ') >= 0) {
      return 0;
    }

    String id = agent.strip();
    Integer known = this.voices.get(id);
    if (known != null) {
      return known;
    }

    this.voices.put(id, this.nextVoice);
    return this.nextVoice++;
  }

  // Attributes and times

  private static boolean isBackground(String role) {
    return "x-bg".equals(role) || "x-background".equals(role);
  }

  private static String localName(String name) {
    int colon = name.indexOf(':');
    return colon < 0 ? name : name.substring(colon + 1);
  }

  private static String attribute(Element element, String local) {
    NamedNodeMap attributes = element.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      Node attribute = attributes.item(i);
      String name = attribute.getNodeName();
      if (!name.startsWith("xmlns") && local.equals(localName(name))) {
        return attribute.getNodeValue();
      }
    }

    return null;
  }

  private static double positiveOr(String number, double otherwise) {
    double value = plainNumber(number);
    return value > 0.0 ? value : otherwise;
  }

  private long time(Element element, String attribute) {
    return this.parseTime(attribute(element, attribute));
  }

  private long endOf(Element element, long begin) {
    long end = this.time(element, "end");
    if (end >= 0L) {
      return end;
    }

    long length = this.time(element, "dur");
    return length >= 0L && begin >= 0L ? begin + length : -1L;
  }

  /** {@code 1:02:03.5}, {@code 2:03.5}, {@code 1:02:03:12} in frames, or a number with h, m, s, ms, f or t, or none. */
  private long parseTime(String value) {
    if (value == null) {
      return -1L;
    }

    String text = value.strip();
    double seconds = text.indexOf(':') >= 0 ? this.clockSeconds(text) : this.offsetSeconds(text);
    if (seconds < 0.0) {
      return -1L;
    }

    double ms = seconds * 1_000.0;
    return ms >= LONGEST_TIME_MS ? -1L : Math.round(ms);
  }

  private double clockSeconds(String text) {
    String[] pieces = text.split(":", -1);
    if (pieces.length < 2 || pieces.length > 4) {
      return -1.0;
    }

    double total = 0.0;
    int wholeTime = Math.min(pieces.length, 3);
    for (int i = 0; i < wholeTime; i++) {
      double piece = plainNumber(pieces[i]);
      if (piece < 0.0) {
        return -1.0;
      }

      total = total * 60.0 + piece;
    }

    if (pieces.length == 4) {
      double frames = plainNumber(pieces[3]);
      if (frames < 0.0) {
        return -1.0;
      }

      total += frames / this.frameRate;
    }

    return total;
  }

  private double offsetSeconds(String text) {
    int numberEnd = 0;
    while (numberEnd < text.length() && (Character.isDigit(text.charAt(numberEnd)) || text.charAt(numberEnd) == '.')) {
      numberEnd++;
    }

    double number = plainNumber(text.substring(0, numberEnd));
    if (number < 0.0) {
      return -1.0;
    }

    return switch (text.substring(numberEnd).strip()) {
      case "", "s" -> number;
      case "h" -> number * 3_600.0;
      case "m" -> number * 60.0;
      case "ms" -> number / 1_000.0;
      case "f" -> number / this.frameRate;
      case "t" -> number / this.tickRate;
      default -> -1.0;
    };
  }

  /** Plain digits with at most one point, or -1: what the JDK's number parser also takes, like NaN and 1e9, is no time. */
  private static double plainNumber(String text) {
    if (text == null || text.isEmpty() || text.length() > 24 || ".".equals(text)) {
      return -1.0;
    }

    boolean point = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '.' && !point) {
        point = true;
      } else if (c < '0' || c > '9') {
        return -1.0;
      }
    }

    return Double.parseDouble(text);
  }

  private static final class Pending {

    TimedLine line;
    TimedLine background;
    boolean backOnly;
    String agent;
    boolean endKnown;
  }

  /** The text of a line in the order of the file, cut where a timed span begins. Text with no time joins what is open. */
  private static final class Stretches {

    final List<Stretch> all = new ArrayList<>();
    final StringBuilder textOut = new StringBuilder();
    private Stretch open;
    private boolean timed;

    void open(long begin, long end) {
      this.open = new Stretch(begin, end);
      this.all.add(this.open);
      this.timed = true;
    }

    void text(String text) {
      if (this.open == null) {
        this.open = new Stretch(-1L, -1L);
        this.all.add(this.open);
      }

      this.open.text.append(text);
    }

    /** Sets out the text with its whitespace made single, and counts each stretch's chars. */
    void tidy(long lineBegin, long lineEnd) {
      boolean space = false;
      long earliest = -1L;
      for (int i = 0; i < this.all.size(); i++) {
        Stretch stretch = this.all.get(i);
        if (stretch.begin >= 0L && earliest < 0L) {
          earliest = stretch.begin;
        }

        CharSequence text = stretch.text;
        for (int c = 0; c < text.length(); c++) {
          char letter = text.charAt(c);
          if (Character.isWhitespace(letter) || Character.isSpaceChar(letter)) {
            space = this.textOut.length() > 0;
          } else {
            if (space) {
              this.textOut.append(' ');
              space = false;
            }

            this.textOut.append(letter);
            stretch.chars++;
          }
        }
      }

      // See readContainer. With the line's end too, as a part in the background may start a little before its line.
      boolean relative = earliest >= 0L && lineBegin >= 0L && earliest < lineBegin
          && (lineEnd < 0L || lineBegin + earliest <= lineEnd);
      if (relative) {
        for (int i = 0; i < this.all.size(); i++) {
          Stretch stretch = this.all.get(i);
          stretch.begin = stretch.begin < 0L ? -1L : stretch.begin + lineBegin;
          stretch.end = stretch.end < 0L ? -1L : stretch.end + lineBegin;
        }
      }

      this.closeLast(lineEnd);
    }

    /** The line's end is the last word's end, when the word itself does not say. */
    private void closeLast(long lineEnd) {
      for (int i = this.all.size() - 1; i >= 0; i--) {
        Stretch stretch = this.all.get(i);
        if (stretch.chars > 0 && stretch.begin >= 0L) {
          if (stretch.end < 0L && lineEnd > stretch.begin) {
            stretch.end = lineEnd;
          }

          return;
        }
      }
    }

    long firstTime() {
      for (int i = 0; i < this.all.size(); i++) {
        Stretch stretch = this.all.get(i);
        if (stretch.chars > 0 && stretch.begin >= 0L) {
          return stretch.begin;
        }
      }

      return -1L;
    }

    SungParts parts(long start) {
      if (!this.timed || this.firstTime() < 0L) {
        return null;
      }

      int count = this.all.size();
      long[] starts = new long[count];
      long[] ends = new long[count];
      int[] chars = new int[count];
      int kept = 0;
      for (int i = 0; i < count; i++) {
        Stretch stretch = this.all.get(i);
        if (stretch.chars == 0) {
          continue;
        }

        if (stretch.begin < 0L) {
          // Text with no time at all goes with what stands before it, or if nothing does, with the line's start.
          if (kept > 0) {
            chars[kept - 1] += stretch.chars;
            continue;
          }

          starts[kept] = 0L;
          ends[kept] = -1L;
        } else {
          starts[kept] = stretch.begin - start;
          ends[kept] = stretch.end < 0L ? -1L : stretch.end - start;
        }

        chars[kept++] = stretch.chars;
      }

      return SungParts.of(starts, ends, chars);
    }
  }

  private static final class Stretch {

    final StringBuilder text = new StringBuilder();
    long begin;
    long end;
    int chars;

    Stretch(long begin, long end) {
      this.begin = begin;
      this.end = end;
    }
  }
}
