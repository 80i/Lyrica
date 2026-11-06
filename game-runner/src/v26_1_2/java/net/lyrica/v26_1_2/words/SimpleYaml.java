package net.lyrica.v26_1_2.words;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the part of YAML a data file needs: mappings, sequences and strings, in block style, with all the ways of
 * writing a string. Maps, lists, strings and null come out; numbers and booleans stay strings. Anything else throws
 * {@link IllegalArgumentException}: a tag, anchor, alias or flow collection other than an empty one, a duplicate key, a
 * second document, or nesting beyond reason. So nothing in a file can make this do more than build those few objects.
 */
final class SimpleYaml {

  private static final int DEEPEST = 16;

  private final String[] lines;
  private int at;

  private SimpleYaml(String text) {
    this.lines = text.split("\\r?\\n", -1);
  }

  static Object parse(String text) {
    SimpleYaml yaml = new SimpleYaml(text);
    String first = yaml.peek();
    if (first != null && first.startsWith("%")) {
      throw new IllegalArgumentException("A directive");
    }

    if (first != null && isDocumentStart(first)) {
      yaml.at++;
    }

    Object root = yaml.node(0, 0);
    if (yaml.peek() != null) {
      throw new IllegalArgumentException("More after the document");
    }

    return root;
  }

  private static boolean isDocumentStart(String line) {
    return "---".equals(line.stripTrailing()) || line.startsWith("--- ");
  }

  /** The next line with something on it, stepping over blanks and comments, or null at the end. */
  private String peek() {
    while (this.at < this.lines.length) {
      String line = this.lines[this.at];
      String content = line.strip();
      if (!content.isEmpty() && !content.startsWith("#")) {
        return "...".equals(line.stripTrailing()) ? null : line;
      }

      this.at++;
    }

    return null;
  }

  private String nextRaw() {
    if (this.at >= this.lines.length) {
      throw new IllegalArgumentException("Ends inside a string");
    }

    return this.lines[this.at++];
  }

  private static int indentOf(String line) {
    int indent = 0;
    while (indent < line.length() && line.charAt(indent) == ' ') {
      indent++;
    }

    if (indent < line.length() && line.charAt(indent) == '\t') {
      throw new IllegalArgumentException("A tab in the indent");
    }

    return indent;
  }

  private Object node(int least, int depth) {
    if (depth > DEEPEST) {
      throw new IllegalArgumentException("Too deep");
    }

    String line = this.peek();
    if (line == null) {
      return null;
    }

    int indent = indentOf(line);
    if (indent < least) {
      return null;
    }

    String content = line.substring(indent);
    if (isItem(content)) {
      return this.sequence(indent, depth);
    }

    if (keyEnd(content) >= 0) {
      return this.mapping(indent, depth);
    }

    this.at++;
    return this.value(content, indent - 1);
  }

  private static boolean isItem(String content) {
    return "-".equals(content.stripTrailing()) || content.startsWith("- ");
  }

  private List<Object> sequence(int indent, int depth) {
    List<Object> items = new ArrayList<>();
    while (true) {
      String line = this.peek();
      if (line == null || indentOf(line) < indent) {
        return items;
      }

      String content = line.substring(indent);
      if (indentOf(line) > indent) {
        throw new IllegalArgumentException("Bad indent");
      }

      if (!isItem(content)) {
        // A key at the indent of the list that was its parent's: a list may stand at the same indent as its key.
        return items;
      }

      String rest = content.substring(1);
      String after = rest.stripLeading();
      if (after.isEmpty() || after.startsWith("#")) {
        this.at++;
        items.add(this.node(indent + 1, depth + 1));
      } else {
        // What follows the dash reads as if it stood on a line of its own, indented as far as it is.
        int inner = indent + 1 + rest.length() - after.length();
        this.lines[this.at] = " ".repeat(inner) + after;
        items.add(this.node(inner, depth + 1));
      }
    }
  }

  private Map<String, Object> mapping(int indent, int depth) {
    Map<String, Object> map = new LinkedHashMap<>();
    while (true) {
      String line = this.peek();
      if (line == null) {
        return map;
      }

      int here = indentOf(line);
      if (here < indent) {
        return map;
      }

      String content = line.substring(here);
      if (here > indent) {
        throw new IllegalArgumentException("Bad indent");
      }

      int end = keyEnd(content);
      if (end < 0) {
        // The next item of a list this mapping is in, or the end of a list at its key's indent.
        if (isItem(content)) {
          return map;
        }

        throw new IllegalArgumentException("Not a key");
      }

      String key = keyText(content.substring(0, end));
      if (map.containsKey(key)) {
        throw new IllegalArgumentException("A duplicate key");
      }

      String rest = content.substring(end + 1).strip();
      this.at++;
      map.put(key, rest.isEmpty() || rest.startsWith("#") ? this.valueBelow(indent, depth) : this.value(rest, indent));
    }
  }

  private Object valueBelow(int indent, int depth) {
    String next = this.peek();
    if (next == null) {
      return null;
    }

    int below = indentOf(next);
    if (below > indent) {
      return this.node(below, depth + 1);
    }

    return below == indent && isItem(next.substring(below)) ? this.sequence(indent, depth + 1) : null;
  }

  /** Where the colon of {@code key: value} is, or -1 if this is no such line. */
  private static int keyEnd(String content) {
    if (isItem(content)) {
      return -1;
    }

    char first = content.charAt(0);
    int from = 0;
    if (first == '"' || first == '\'') {
      from = content.indexOf(first, 1);
      if (from < 0) {
        return -1;
      }

      from++;
      return from < content.length() && content.charAt(from) == ':' && isAfterColon(content, from) ? from : -1;
    }

    for (int i = from; i < content.length(); i++) {
      if (content.charAt(i) == ':' && isAfterColon(content, i)) {
        return i;
      }
    }

    return -1;
  }

  private static boolean isAfterColon(String content, int colon) {
    return colon + 1 == content.length() || content.charAt(colon + 1) == ' ';
  }

  private static String keyText(String raw) {
    String key = raw.strip();
    if (key.length() >= 2 && (key.charAt(0) == '"' || key.charAt(0) == '\'')) {
      return key.substring(1, key.length() - 1);
    }

    return key;
  }

  /** A value that starts on a line already stepped past. Lines indented beyond {@code parent} can continue it. */
  private Object value(String text, int parent) {
    char first = text.charAt(0);
    return switch (first) {
      case '|', '>' -> this.block(text, parent);
      case '"' -> this.doubleQuoted(text);
      case '\'' -> this.singleQuoted(text);
      case '[' -> {
        if (!isEmptyFlow(text, ']')) {
          throw new IllegalArgumentException("A flow list");
        }

        yield new ArrayList<>();
      }
      case '{' -> {
        if (!isEmptyFlow(text, '}')) {
          throw new IllegalArgumentException("A flow mapping");
        }

        yield new LinkedHashMap<String, Object>();
      }
      case '&', '*', '!', '%', '@', '`' -> throw new IllegalArgumentException("Not plain data: " + first);
      default -> this.plain(text, parent);
    };
  }

  private static boolean isEmptyFlow(String text, char close) {
    String rest = stripComment(text.substring(1).stripLeading());
    return rest.length() == 1 && rest.charAt(0) == close;
  }

  private String plain(String first, int parent) {
    StringBuilder out = new StringBuilder(stripComment(first));
    boolean commented = first.length() != out.length();
    while (!commented) {
      String next = this.peek();
      if (next == null || indentOf(next) <= parent) {
        break;
      }

      String content = next.strip();
      if (keyEnd(content) >= 0) {
        throw new IllegalArgumentException("A key indented under a value");
      }

      String kept = stripComment(content);
      commented = kept.length() != content.length();
      out.append(' ').append(kept);
      this.at++;
    }

    String text = out.toString();
    return "~".equals(text) || "null".equals(text) || "Null".equals(text) || "NULL".equals(text) ? null : text;
  }

  private static String stripComment(String text) {
    int mark = text.indexOf(" #");
    return (mark < 0 ? text : text.substring(0, mark)).stripTrailing();
  }

  private String singleQuoted(String first) {
    StringBuilder out = new StringBuilder();
    String line = first;
    int i = 1;
    while (true) {
      while (i < line.length()) {
        char c = line.charAt(i++);
        if (c != '\'') {
          out.append(c);
        } else if (i < line.length() && line.charAt(i) == '\'') {
          out.append('\'');
          i++;
        } else {
          endOfString(line.substring(i));
          return out.toString();
        }
      }

      line = this.foldInto(out);
      i = 0;
    }
  }

  private String doubleQuoted(String first) {
    StringBuilder out = new StringBuilder();
    String line = first;
    int i = 1;
    while (true) {
      while (i < line.length()) {
        char c = line.charAt(i++);
        if (c == '"') {
          endOfString(line.substring(i));
          return out.toString();
        }

        if (c != '\\') {
          out.append(c);
        } else if (i >= line.length()) {
          // An escaped line end joins the lines with nothing between them.
          line = this.nextRaw().stripLeading();
          i = 0;
        } else {
          i = escape(line, i, out);
        }
      }

      line = this.foldInto(out);
      i = 0;
    }
  }

  /** A line end inside a quoted string is a space, and a run of blank lines is that many line ends. */
  private String foldInto(StringBuilder out) {
    int keep = out.length();
    while (keep > 0 && (out.charAt(keep - 1) == ' ' || out.charAt(keep - 1) == '\t')) {
      keep--;
    }

    out.setLength(keep);
    String next = this.nextRaw();
    int blanks = 0;
    while (next.isBlank()) {
      blanks++;
      next = this.nextRaw();
    }

    out.append(blanks == 0 ? " " : "\n".repeat(blanks));
    return next.stripLeading();
  }

  private static void endOfString(String after) {
    String rest = after.strip();
    if (!rest.isEmpty() && !rest.startsWith("#")) {
      throw new IllegalArgumentException("Something after a string");
    }
  }

  private static int escape(String line, int at, StringBuilder out) {
    char c = line.charAt(at);
    switch (c) {
      case '0' -> out.append('\0');
      case 'a' -> out.append('\u0007');
      case 'b' -> out.append('\b');
      case 't', '\t' -> out.append('\t');
      case 'n' -> out.append('\n');
      case 'v' -> out.append('\u000B');
      case 'f' -> out.append('\f');
      case 'r' -> out.append('\r');
      case 'e' -> out.append('\u001B');
      case ' ' -> out.append(' ');
      case '"' -> out.append('"');
      case '/' -> out.append('/');
      case '\\' -> out.append('\\');
      case 'N' -> out.append('\u0085');
      case '_' -> out.append(' ');
      case 'L' -> out.append(' ');
      case 'P' -> out.append(' ');
      case 'x' -> {
        return hex(line, at + 1, 2, out);
      }
      case 'u' -> {
        return hex(line, at + 1, 4, out);
      }
      case 'U' -> {
        return hex(line, at + 1, 8, out);
      }
      default -> throw new IllegalArgumentException("An unknown escape");
    }

    return at + 1;
  }

  private static int hex(String line, int from, int digits, StringBuilder out) {
    if (from + digits > line.length()) {
      throw new IllegalArgumentException("A short escape");
    }

    int point = 0;
    for (int i = from; i < from + digits; i++) {
      int digit = Character.digit(line.charAt(i), 16);
      if (digit < 0) {
        throw new IllegalArgumentException("A bad escape");
      }

      point = point * 16 + digit;
    }

    if (!Character.isValidCodePoint(point)) {
      throw new IllegalArgumentException("A bad escape");
    }

    out.appendCodePoint(point);
    return from + digits;
  }

  private String block(String header, int parent) {
    boolean folded = header.charAt(0) == '>';
    int indent = -1;
    for (int i = 1; i < header.length() && header.charAt(i) != ' '; i++) {
      char c = header.charAt(i);
      if (c >= '1' && c <= '9') {
        indent = Math.max(parent, 0) + (c - '0');
      } else if (c != '+' && c != '-') {
        throw new IllegalArgumentException("A bad block header");
      }
    }

    StringBuilder out = new StringBuilder();
    while (this.at < this.lines.length) {
      String line = this.lines[this.at];
      if (line.isBlank()) {
        out.append('\n');
        this.at++;
        continue;
      }

      int here = indentOf(line);
      if (indent < 0) {
        indent = here;
      }

      if (here < indent || here <= parent) {
        break;
      }

      out.append(line.substring(indent)).append(folded ? ' ' : '\n');
      this.at++;
    }

    return out.toString().stripTrailing();
  }
}
