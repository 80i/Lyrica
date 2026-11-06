package net.lyrica.v26_2.words;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Development only: what the lyric readers must do with good and with bad files. {@code LetterSmoke} runs this in the
 * game. It uses nothing but the JDK and the readers, so it also runs on its own. All words here are made up for it.
 */
public final class ReaderChecks {

  private static final String HEAD = "<tt xmlns=\"http://www.w3.org/ns/ttml\" "
      + "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" xmlns:ttp=\"http://www.w3.org/ns/ttml#parameter\">";
  private static final String AGENTS = "<head><metadata>"
      + "<ttm:agent type=\"person\" xml:id=\"v1\"/><ttm:agent type=\"person\" xml:id=\"v2\"/>"
      + "<ttm:agent type=\"group\" xml:id=\"v1000\"/></metadata></head>";

  private final List<String> failures = new ArrayList<>();

  private ReaderChecks() {
  }

  /** What went wrong, empty when all is well. */
  public static List<String> failures() {
    ReaderChecks checks = new ReaderChecks();
    Runnable[] groups = {checks::words, checks::times, checks::background, checks::duet, checks::nesting,
        checks::badFiles, checks::hostileFiles, checks::lrc, checks::yaml, checks::lyricsfile, checks::badLyricsfiles,
        checks::names};
    for (Runnable group : groups) {
      try {
        group.run();
      } catch (RuntimeException failure) {
        // A line that was wanted and is not there, say. The rest can still say what they know.
        checks.failures.add(failure.toString() + " at " + failure.getStackTrace()[0] + ", "
            + failure.getStackTrace()[Math.min(1, failure.getStackTrace().length - 1)]);
      }
    }

    return checks.failures;
  }

  private void words() {
    TimedLyrics song = TtmlReader.parse(HEAD + "<body><div><p begin=\"1.000\" end=\"3.000\">"
        + "<span begin=\"1.000\" end=\"1.400\">Lan</span><span begin=\"1.400\" end=\"1.800\">tern</span> "
        + "<span begin=\"2.000\" end=\"3.000\">light</span>"
        + "<span ttm:role=\"x-translation\" xml:lang=\"de\">Laternenlicht</span>"
        + "<span ttm:role=\"x-roman\">nope</span></p></div></body></tt>");
    this.same("one line", 1, song.size());
    TimedLine line = song.get(0);
    this.same("syllables join, translation stays out", "Lantern light", line.text());
    this.same("start", 1_000L, line.startMs());
    this.same("first sung", 1_000L, line.firstSungMs());
    this.same("sung end", 3_000L, line.sungEndMs());
    this.same("the line lingers as it is the last", 4_500L, line.endMs());
    SungParts parts = line.parts();
    this.same("parts", 3, parts.count());
    this.same("second syllable", 400L, parts.start(1));
    this.same("its end", 800L, parts.end(1));
    this.same("letters of the third part", 5, parts.chars(2));
    this.near("the second letter of the first part", 133.3F, parts.startAt(1.0F));
    this.near("the end of the word", 800.0F, parts.endAt(7.0F));
    this.near("the next word starts at its own time", 1_000.0F, parts.startAt(7.0F));

    TimedLyrics spaced = TtmlReader.parse(HEAD + "<body><p begin=\"0\" end=\"1\"><span begin=\"0\" end=\"0.5\">one </span>"
        + "\n   <span begin=\"0.5\" end=\"1\">&#160;two&amp;</span></p></body></tt>");
    this.same("spaces inside and between spans make one", "one two&", spaced.get(0).text());

    TimedLyrics noSpaces = TtmlReader.parse(HEAD + "<body><p begin=\"5\" end=\"6\"><span begin=\"5\" end=\"5.5\">\u6708</span>"
        + "<span begin=\"5.5\" end=\"6\">\u4eae</span></p></body></tt>");
    this.same("characters stay one word", "\u6708\u4eae", noSpaces.get(0).text());
    this.same("each with its time", 2, noSpaces.get(0).parts().count());

    TimedLyrics linesOnly = TtmlReader.parse(HEAD + "<body><p begin=\"1\" end=\"2\">Only a line</p>"
        + "<p begin=\"9\">Without an end</p></body></tt>", 10_000L);
    this.same("a line without words has no parts", null, linesOnly.get(0).parts());
    this.same("its end stands", 2_000L, linesOnly.get(0).endMs());
    this.same("no end means the song's end at most", 10_000L, linesOnly.get(1).endMs());

    TimedLyrics marks = TtmlReader.parse(HEAD + "<body><p begin=\"1\" end=\"2\">\u266a \u266b</p>"
        + "<p begin=\"3\" end=\"4\">words</p></body></tt>");
    this.same("music marks are no line", 1, marks.size());
  }

  private void times() {
    this.time("seconds", 12_345L, "12.345");
    this.time("with s", 12_500L, "12.5s");
    this.time("minutes", 62_500L, "1:02.5");
    this.time("hours", 3_723_500L, "01:02:03.5");
    this.time("frames", 3_723_500L, "01:02:03:15");
    this.time("ms", 250L, "250ms");
    this.time("m", 90_000L, "1.5m");
    this.time("h", 3_600_000L, "1h");
    this.time("f", 1_000L, "30f");
    TimedLyrics rates = TtmlReader.parse("<tt ttp:frameRate=\"24\" ttp:tickRate=\"10000\"><body>"
        + "<p begin=\"24f\" end=\"20000t\">rates</p></body></tt>");
    this.same("a file's frame rate", 1_000L, rates.get(0).startMs());
    this.same("a file's tick rate", 2_000L, rates.get(0).endMs());

    for (String bad : new String[] {"NaN", "1e9", "-3", "abc", "1:2:3:4:5", "", "99999999999999"}) {
      this.same("no time in " + bad, 0, TtmlReader.parse(HEAD + "<body><p begin=\"" + bad + "\">x</p></body></tt>").size());
    }

    TimedLyrics withDur = TtmlReader.parse(HEAD + "<body><p begin=\"2\" dur=\"1\">"
        + "<span begin=\"2\" dur=\"0.25\">dur</span></p></body></tt>");
    this.same("dur gives the end", 250L, withDur.get(0).parts().end(0));
  }

  private void time(String what, long ms, String value) {
    TimedLyrics song = TtmlReader.parse(HEAD + "<body><p begin=\"" + value + "\">x</p></body></tt>");
    this.same(what + " line", 1, song.size());
    if (song.size() == 1) {
      this.same(what, ms, song.get(0).startMs());
    }
  }

  private void background() {
    TimedLyrics song = TtmlReader.parse(HEAD + "<body><p begin=\"1\" end=\"2\">"
        + "<span begin=\"1\" end=\"2\">Main</span>"
        + "<span ttm:role=\"x-bg\"><span begin=\"1.5\" end=\"2\">(echo</span> <span begin=\"2\" end=\"3\">here)</span></span>"
        + "</p><p begin=\"10\" end=\"11\"><span begin=\"10\" end=\"11\">Next</span></p>"
        + "<p begin=\"10.5\" end=\"11\" ttm:role=\"x-bg\"><span begin=\"10.5\" end=\"11\">own</span></p>"
        + "<p begin=\"20\" end=\"21\" ttm:role=\"x-bg\"><span begin=\"20\" end=\"21\">alone</span></p>"
        + "</body></tt>");
    this.same("background parts are not lines", 3, song.size());
    TimedLine line = song.get(0);
    this.same("main words without them", "Main", line.text());
    this.same("the background's words", "(echo here)", line.background().text());
    this.same("the background's start", 1_500L, line.background().startMs());
    this.same("the line waits for the background", 3_000L, line.sungEndMs());
    this.same("the background's own p joins the line it is under", "own", song.get(1).background().text());
    this.same("with nothing to go under it is a line", "alone", song.get(2).text());
  }

  private void duet() {
    TimedLyrics song = TtmlReader.parse(HEAD + AGENTS + "<body>"
        + "<p begin=\"1\" end=\"3\" ttm:agent=\"v1\"><span begin=\"1\" end=\"3\">one</span></p>"
        + "<p begin=\"2\" end=\"4\" ttm:agent=\"v2\"><span begin=\"2\" end=\"4\">two</span></p>"
        + "<p begin=\"5\" end=\"6\" ttm:agent=\"v1000\"><span begin=\"5\" end=\"6\">all</span></p>"
        + "</body></tt>");
    this.same("first singer", 1, song.get(0).voice());
    this.same("second singer", 2, song.get(1).voice());
    this.same("a group is nobody's side", 0, song.get(2).voice());
    this.same("lines may overlap", 3_000L, song.get(0).sungEndMs());
    this.same("no linger past the sung end when the next has begun", 3_000L, song.get(0).endMs());
    this.same("the first is not cut off", "two", song.get(song.indexAt(2_500L)).text());

    TimedLyrics solo = TtmlReader.parse(HEAD + AGENTS + "<body>"
        + "<p begin=\"1\" end=\"3\" ttm:agent=\"v2\"><span begin=\"1\" end=\"3\">one</span></p></body></tt>");
    this.same("one singer is no duet", 0, solo.get(0).voice());

    TimedLyrics unlisted = TtmlReader.parse("<tt><body><p begin=\"1\" ttm:agent=\"a\">one</p>"
        + "<p begin=\"2\" ttm:agent=\"b\">two</p></body></tt>");
    this.same("undeclared prefixes and singers still read", 2, unlisted.get(1).voice());
  }

  private void nesting() {
    TimedLyrics absolute = TtmlReader.parse(HEAD + "<body begin=\"0\"><div begin=\"9.5\" end=\"20\">"
        + "<p begin=\"9.5\" end=\"11\"><span begin=\"9.5\" end=\"11\">same</span></p></div></body></tt>");
    this.same("a div's start repeated on its lines is not added", 9_500L, absolute.get(0).startMs());

    TimedLyrics relative = TtmlReader.parse(HEAD + "<body><div begin=\"10\">"
        + "<p begin=\"1\" end=\"3\"><span begin=\"0.5\" end=\"1\">under</span></p></div></body></tt>");
    this.same("times under a later div count from it", 11_000L, relative.get(0).startMs());
    this.same("span under a later p counts from it", 500L, relative.get(0).parts().start(0));
    this.same("first sung", 11_500L, relative.get(0).firstSungMs());

    TimedLyrics early = TtmlReader.parse(HEAD + "<body><p begin=\"10\" end=\"12\"><span begin=\"10\" end=\"12\">word</span>"
        + "<span ttm:role=\"x-bg\"><span begin=\"9.5\" end=\"10\">early</span></span></p></body></tt>");
    this.same("a background part a little early is not moved", 9_500L, early.get(0).background().startMs());

    TimedLyrics spans = TtmlReader.parse(HEAD + "<body><p begin=\"1\" end=\"2\">"
        + "<span begin=\"1\" end=\"2\"><span begin=\"1\" end=\"1.5\">in</span><span begin=\"1.5\" end=\"2\">ner</span></span>"
        + "</p></body></tt>");
    this.same("spans in a span", "inner", spans.get(0).text());
    this.same("the inner ones count", 2, spans.get(0).parts().count());

    StringBuilder deep = new StringBuilder(HEAD + "<body><p begin=\"1\">x");
    for (int i = 0; i < 20_000; i++) {
      deep.append("<span>");
    }

    deep.append("y");
    for (int i = 0; i < 20_000; i++) {
      deep.append("</span>");
    }

    deep.append("</p></body></tt>");
    // Whether the parser takes this depends on the JVM's limits, and refusing it is fine. It only must not blow up.
    TimedLyrics tooDeep = TtmlReader.parse(deep.toString());
    this.same("very deep nesting does no harm", true, tooDeep.isEmpty() || "x".equals(tooDeep.get(0).text()));
  }

  private void badFiles() {
    String[] bad = {
        null,
        "",
        "   ",
        "not xml at all",
        "<",
        "<tt><body><p begin=\"1\">cut off",
        "<tt><body><p begin=\"1\"><span>wrong</p></span></body></tt>",
        "<tt></tt>",
        "<html><body>no lyrics</body></html>",
        "<?xml version=\"1.0\"?>",
        "\u0000\u0001\u0002",
    };
    for (String text : bad) {
      this.same("nothing from " + text, 0, TtmlReader.parse(text).size());
    }

    TimedLyrics mixed = TtmlReader.parse(HEAD + "<body>"
        + "<p>no time at all</p>"
        + "<p begin=\"oops\">bad time</p>"
        + "<p begin=\"5\" end=\"4\">end before start</p>"
        + "<p begin=\"1\" end=\"2\"></p>"
        + "<p begin=\"7\" end=\"8\"><span begin=\"7.5\" end=\"7\">backward</span> <span end=\"8\">untimed</span>"
        + " <span begin=\"7.2\" end=\"7.3\">earlier</span></p>"
        + "</body></tt>");
    this.same("a bad line costs only itself", 2, mixed.size());
    this.same("a bad end means no end", true, mixed.get(0).endMs() > 5_000L);
    TimedLine odd = mixed.get(1);
    this.same("words survive in their order", "backward untimed earlier", odd.text());
    this.same("times never run back", true, odd.parts().start(1) >= odd.parts().start(0));
    this.same("every part has a length", true, odd.parts().end(1) > odd.parts().start(1));
  }

  private void hostileFiles() {
    String secret = "<!DOCTYPE tt [<!ENTITY leak SYSTEM \"file:///C:/Windows/win.ini\">]>"
        + "<tt><body><p begin=\"1\">&leak;</p></body></tt>";
    this.same("an external entity is refused", 0, TtmlReader.parse(secret).size());

    String remote = "<!DOCTYPE tt SYSTEM \"http://127.0.0.1:9/evil.dtd\"><tt><body><p begin=\"1\">x</p></body></tt>";
    this.same("an external document type is refused", 0, TtmlReader.parse(remote).size());

    StringBuilder laughs = new StringBuilder("<!DOCTYPE tt [<!ENTITY a \"aaaaaaaaaa\">");
    char previous = 'a';
    for (char c = 'b'; c <= 'k'; c++) {
      laughs.append("<!ENTITY ").append(c).append(" \"");
      for (int i = 0; i < 10; i++) {
        laughs.append('&').append(previous).append(';');
      }

      laughs.append("\">");
      previous = c;
    }

    laughs.append("]><tt><body><p begin=\"1\">&k;</p></body></tt>");
    long before = System.currentTimeMillis();
    this.same("expanding entities are refused", 0, TtmlReader.parse(laughs.toString()).size());
    this.same("and at once", true, System.currentTimeMillis() - before < 2_000L);
  }

  private void lrc() {
    TimedLyrics song = LrcReader.parse("[ar:Nobody]\n[00:01.00]<00:01.00>one <00:01.50>two <00:02.00>three\n"
        + "[00:20.00]later line\n");
    this.same("two lines", 2, song.size());
    TimedLine line = song.get(0);
    this.same("words", 3, line.parts().count());
    this.same("start of the second word", 500L, line.parts().start(1));
    this.same("an end is taken from the next word", 1_000L, line.parts().end(1));
    this.same("the last word's length is a guess", 2_600L, line.parts().end(2));
    this.same("the line leaves a while after the last word", 1_000L + 2_600L + 1_500L, line.endMs());
    this.same("a plain line has no parts", null, song.get(1).parts());

    TimedLine ended = LrcReader.parse("[00:01.00]<00:01.00>held<00:03.00>\n[00:09.00]next\n").get(0);
    this.same("a last tag is the end", 2_000L, ended.parts().end(0));
    this.same("the text has no tags", "held", ended.text());

    TimedLine syllables = LrcReader.parse("[00:01.00]<00:01.00>Lan<00:01.20>tern <00:01.60>glow\n").get(0);
    this.same("syllables", 3, syllables.parts().count());
    this.same("one word and another", "Lantern glow", syllables.text());

    TimedLine gapped = LrcReader.parse("[00:01.00]<00:01.00>one <00:01.50> <00:02.00>two\n").get(0);
    this.same("a tag between words ends the word", 500L, gapped.parts().end(0));
    this.same("and the next tag starts the next", 1_000L, gapped.parts().start(1));

    TimedLyrics repeated = LrcReader.parse("[00:01.00][00:11.00]<00:01.00>again <00:01.50>and\n");
    this.same("a line sung twice", 2, repeated.size());
    this.same("the second time has the same words", 500L, repeated.get(1).parts().start(1));
    this.same("at its own time", 11_000L, repeated.get(1).startMs());

    TimedLyrics offset = LrcReader.parse("[offset:500]\n[00:01.00]<00:01.00>a <00:01.50>b\n");
    this.same("offset", 500L, offset.get(0).startMs());
    this.same("offset on words", 500L, offset.get(0).parts().start(1));

    TimedLine plain = LrcReader.parse("[00:01.00]plain\n[00:02.00]next\n").get(0);
    this.same("a plain line still runs up to the next", 2_000L, plain.endMs());
    this.same("nor does it have a sung end of its own", 2_000L, plain.sungEndMs());
  }

  @SuppressWarnings("unchecked")
  private void yaml() {
    Map<String, Object> root = (Map<String, Object>) SimpleYaml.parse(String.join("\n",
        "---",
        "# a comment",
        "plain: some words # and a comment",
        "single: 'it''s # kept'",
        "double: \"a\\tb \\u00e9 \\\"q\\\" \"",
        "colon: \"key: value\"",
        "url: http://example.com/a",
        "blank:",
        "nothing: ~",
        "empty_list: []",
        "empty_map: {}",
        "folded_plain: one",
        "  two",
        "folded_quote: \"one",
        "  two\"",
        "literal: |",
        "  first",
        "    second",
        "",
        "  third",
        "fold: >-",
        "  first",
        "  second",
        "at_key_indent:",
        "- a",
        "- b",
        "indented:",
        "  - c: 1",
        "    d: 2",
        "  - c: 3",
        "    e:",
        "      - f",
        "after: end",
        "..."));
    this.same("plain", "some words", root.get("plain"));
    this.same("single", "it's # kept", root.get("single"));
    this.same("double", "a\tb \u00e9 \"q\" ", root.get("double"));
    this.same("a colon in quotes", "key: value", root.get("colon"));
    this.same("a colon with no space", "http://example.com/a", root.get("url"));
    this.same("no value", null, root.get("blank"));
    this.same("null", null, root.get("nothing"));
    this.same("an empty list", List.of(), root.get("empty_list"));
    this.same("an empty map", Map.of(), root.get("empty_map"));
    this.same("a plain string on two lines", "one two", root.get("folded_plain"));
    this.same("a quoted string on two lines", "one two", root.get("folded_quote"));
    this.same("a literal block", "first\n  second\n\nthird", root.get("literal"));
    this.same("a folded block", "first second", root.get("fold"));
    this.same("a list at its key's indent", List.of("a", "b"), root.get("at_key_indent"));
    List<Map<String, Object>> indented = (List<Map<String, Object>>) root.get("indented");
    this.same("mappings in a list", "2", indented.get(0).get("d"));
    this.same("a list in that", List.of("f"), indented.get(1).get("e"));
    this.same("what comes after", "end", root.get("after"));

    String[] refused = {
        "a: 1\na: 2", "a: !!python/object x", "a: &one 1\nb: *one", "a: [1, 2]", "a: {b: 1}", "a: \"open", "a: 'open",
        "a: \"\\q\"", "a: \"x\" y", "\ta: 1", "a: 1\n---\nb: 2", "%YAML 1.2\n---\na: 1", "a: 1\n    b: 2",
    };
    for (String bad : refused) {
      try {
        SimpleYaml.parse(bad);
        this.failures.add("taken: " + bad);
      } catch (IllegalArgumentException expected) {
        // As it should be.
      }
    }

    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      deep.append(" ".repeat(i)).append("a:\n");
    }

    try {
      SimpleYaml.parse(deep.toString());
      this.failures.add("very deep nesting was taken");
    } catch (IllegalArgumentException expected) {
      // As it should be.
    }
  }

  private void lyricsfile() {
    TimedLyrics song = LyricsfileReader.parse(String.join("\n",
        "version: \"1.0\"",
        "metadata:",
        "  title: Made Up",
        "  artist: Nobody",
        "  duration_ms: 90000",
        "lines:",
        "  - text: Over the hills.",
        "    words:",
        "      - text: \"Over \"",
        "        start_ms: 1000",
        "      - text: \"the \"",
        "        start_ms: 1500",
        "        end_ms: 1700",
        "      - text: hills.",
        "        start_ms: 2000",
        "    start_ms: 1000",
        "    end_ms: 9000",
        "  - text: 'Mid-air: and on'",
        "    start_ms: 9000",
        "    end_ms: 11000",
        "  - text: \u6708\u4eae",
        "    start_ms: 12000",
        "    end_ms: 13000",
        "    words:",
        "      - {}",
        "  - text: \u6708\u4eae\u6765",
        "    start_ms: 14000",
        "    words:",
        "      - text: \u6708",
        "        start_ms: 14000",
        "        end_ms: 14300",
        "      - text: \u4eae",
        "        start_ms: 14300",
        "        end_ms: 14600",
        "      - text: \u6765",
        "        start_ms: 14600",
        "        end_ms: 15000",
        "  - text: Same start",
        "    start_ms: 20000",
        "    end_ms: 22000",
        "    words:",
        "      - text: \"Same \"",
        "        start_ms: 19500",
        "      - text: start",
        "        start_ms: 20500",
        "plain: |",
        "  version: not this one",
        "  lines:"), 90_000L);
    this.same("lines", 5, song.size());
    this.same("word timed", true, song.wordTimed());
    TimedLine line = song.get(0);
    this.same("the line's text", "Over the hills.", line.text());
    this.same("three words", 3, line.parts().count());
    this.same("the first word runs to the next", 500L, line.parts().end(0));
    this.same("the second has its own end", 700L, line.parts().end(1));
    this.same("the next line's start is not when the last word stopped", 2_600L, line.parts().end(2));
    this.same("but the line stays as long as it says", 9_000L, line.endMs());
    this.same("so the singing ends with the last word", 3_600L, line.sungEndMs());
    this.same("a line with no words", null, song.get(1).parts());
    this.same("a line with a bad word stays a line", null, song.get(2).parts());
    this.same("no spaces in the words", 3, song.get(3).parts().count());
    this.same("the words' own end, and no linger past the song's", 16_500L, song.get(3).endMs());
    this.same("a word before its line moves the line", 19_500L, song.get(4).startMs());

    TimedLine spaces = LyricsfileReader.parse(String.join("\n",
        "version: '1.0'",
        "metadata:",
        "  title: t",
        "  artist: a",
        "lines:",
        "- text: \"Hello world\"",
        "  start_ms: 100",
        "  words:",
        "  - text: Hello",
        "    start_ms: 100",
        "  - text: world",
        "    start_ms: 500"), 0L).get(0);
    this.same("words that forgot their spaces, and lists at their key's indent", "Hello world", spaces.text());
    this.same("still timed", 2, spaces.parts().count());

    TimedLine other = LyricsfileReader.parse(String.join("\n",
        "version: '1.0'",
        "lines:",
        "  - text: This is the line",
        "    start_ms: 100",
        "    words:",
        "      - text: Something else",
        "        start_ms: 100"), 0L).get(0);
    this.same("words of some other text are not used", null, other.parts());
    this.same("and the line remains", "This is the line", other.text());
  }

  private void badLyricsfiles() {
    String[] nothing = {
        null,
        "",
        "not yaml: [",
        "just some words",
        "version: '2.0'\nlines:\n  - text: a\n    start_ms: 1",
        "lines:\n  - text: a\n    start_ms: 1",
        "version: '1.0'",
        "version: '1.0'\nlines: []",
        "version: '1.0'\nlines: nope",
        "version: '1.0'\nlines:\n  - nope",
        "version: '1.0'\nlines:\n  - text: no time",
        "version: '1.0'\nlines:\n  - start_ms: 1",
        "version: '1.0'\nlines:\n  - text: bad time\n    start_ms: soon",
        "version: '1.0'\nlines:\n  - text: negative\n    start_ms: -5",
        "version: '1.0'\nlines:\n  - text: huge\n    start_ms: 999999999999",
        "version: '1.0'\nlines:\n  - text: \u266a\n    start_ms: 1",
    };
    for (String text : nothing) {
      this.same("nothing from " + text, 0, LyricsfileReader.parse(text, 0L).size());
    }

    TimedLyrics endless = LyricsfileReader.parse("version: '1.0'\nlines:\n  - text: a\n    start_ms: 5000\n    end_ms: 100\n"
        + "  - text: b\n    start_ms: 9000", 0L);
    this.same("an end before the start means no end", 9_000L, endless.get(0).endMs());
  }

  /** The Arabic is written as escapes so that this file stays plain to read in any editor. */
  private void names() {
    String[][] alike = {
        {"\u0639\u0645\u0631\u0648 \u062F\u064A\u0627\u0628", "Amr Diab"},
        {"\u062A\u0645\u0644\u064A \u0645\u0639\u0627\u0643", "Tamally Maak"},
        {"\u0646\u0633\u0645 \u0639\u0644\u064A\u0646\u0627 \u0627\u0644\u0647\u0648\u0649", "Nassam Alayna El Hawa"},
        {"\u0634\u064A\u0631\u064A\u0646", "Sherine"},
        {"\u0645\u0634\u0627\u0639\u0631", "Masha'er"},
        {"\u0627\u0644\u0628\u062E\u062A", "El Bakht"},
        {"\u0648\u064A\u062C\u0632", "Wegz"},
        {"\u0623\u0645 \u0643\u0644\u062B\u0648\u0645", "Om Kalthoum"},
    };
    for (String[] pair : alike) {
      this.same(pair[1] + " sounds like its Arabic", true, NameMatch.closeness(pair[0], pair[1]) >= 0.7);
      this.same(pair[1] + " both ways round", NameMatch.sounded(pair[0], pair[1]), NameMatch.sounded(pair[1], pair[0]));
    }

    String[][] different = {
        {"\u062A\u0645\u0644\u064A \u0645\u0639\u0627\u0643", "Boshret Kheir"},
        {"\u062D\u0628\u0643 \u0644\u064A\u0627", "Tamally Maak"},
        {"\u0639\u0645\u0631\u0648 \u062F\u064A\u0627\u0628", "Nancy Ajram"},
        {"\u0645\u0634\u0627\u0639\u0631", "Hello"},
        {"\u0634\u064A\u0631\u064A\u0646", "Dynamite"},
    };
    for (String[] pair : different) {
      this.same(pair[1] + " is not that Arabic", true, NameMatch.closeness(pair[0], pair[1]) < 0.7);
    }

    this.same("the same script is not compared by sound", 0.0, NameMatch.sounded("Tamally Maak", "Tmly Mk"));
    this.same("nor Arabic with Arabic", 0.0, NameMatch.sounded("\u0634\u064A\u0631\u064A\u0646", "\u0645\u0634\u0627\u0639\u0631"));
    this.same("nor Arabic with what has no Latin letters", 0.0, NameMatch.sounded("\u0634\u064A\u0631\u064A\u0646", "\u6708\u4EAE"));
    this.same("an empty name is like nothing", 0.0, NameMatch.closeness("", "Sherine"));
    this.same("marks and hamza do not count", 1.0, NameMatch.written("\u0623\u062D\u0645\u062F", "\u0627\u062D\u0645\u062F"));
    this.same("ta marbuta and ha are one spelling", 1.0, NameMatch.written("\u0644\u064A\u0644\u0629", "\u0644\u064A\u0644\u0647"));
    this.same("alif maqsura and ya are one spelling", 1.0, NameMatch.written("\u0647\u0648\u0649", "\u0647\u0648\u064A"));
    this.same("the stretching line does not count", 1.0, NameMatch.written("\u0644\u0640\u064A\u0644", "\u0644\u064A\u0644"));
  }

  private void same(String what, Object wanted, Object found) {
    if (wanted == null ? found != null : !wanted.equals(found)) {
      this.failures.add(what + ": wanted " + wanted + ", found " + found);
    }
  }

  private void near(String what, float wanted, float found) {
    if (Math.abs(wanted - found) > 0.5F) {
      this.failures.add(what + ": wanted " + wanted + ", found " + found);
    }
  }
}
