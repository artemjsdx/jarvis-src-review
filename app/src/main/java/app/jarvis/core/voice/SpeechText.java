package app.jarvis.core.voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns assistant text into text that is pleasant to hear: strips markdown/emoji/URLs, expands
 * numbers, dates, times, ordinals, decimals, percents and unit abbreviations into Russian words
 * (with grammatical gender/case where cheaply decidable), handles emotion tags and splits into
 * speakable chunks. Pure Java, conservative: anything it cannot confidently interpret is left
 * untouched, so words are never mangled.
 *
 * Design notes:
 *  - Digits adjacent to ".", "-" or ":" are left alone (ranges "3-5", phones "123-45-67",
 *    versions "1.2.3", unconverted decimals "3.14" all survive untouched).
 *  - Dates use a 4-digit year only; "28.10.2026" → "двадцать восьмое октября две тысячи
 *    двадцать шестого года" (nominative, as when answering "какое сегодня число").
 *  - Gender of "1"/"2" before a noun is guessed from the noun's ending: nominative feminines end
 *    in -а/-я ("одна минута"), genitives after 2 end in -ы/-и for feminines ("две минуты") while
 *    -а/-я after 2 is a masculine/neuter genitive ("два часа", "два письма"). ь-words are the only
 *    ambiguous group and are resolved by a small table (день/рубль → masculine, ночь/дверь → feminine).
 */
public final class SpeechText {

    /** Rendering options. */
    public static final class Opts {
        /** True only for providers that understand [whispers]-style tags (ElevenLabs v3/v4). */
        public boolean keepEmotionTags = false;
        /** User pronunciation rules, applied after everything else. May be null. */
        public PronunciationDict dict;

        /** Spoken in place of a fenced code block; null/empty = the block is dropped silently. */
        public String codeMarker;

        public Opts keepEmotionTags(boolean v) { keepEmotionTags = v; return this; }

        public Opts codeMarker(String v) { codeMarker = v; return this; }

        public Opts dict(PronunciationDict d) { dict = d; return this; }
    }

    private SpeechText() { }

    public static String forSpeech(String text) {
        return forSpeech(text, new Opts());
    }

    public static String forSpeech(String text, Opts opts) {
        if (opts == null) opts = new Opts();
        if (text == null) return "";
        String s = clean(text, opts);
        if (s.isEmpty()) return "";
        s = convertDates(s);
        s = convertTimes(s);
        s = convertYearWords(s);
        s = convertOrdinals(s);
        s = convertNumbers(s);
        s = expandAbbrevs(s);
        if (opts.dict != null) s = opts.dict.apply(s);
        s = s.replaceAll("\\s+([,.!?;:…])", "$1");
        s = s.trim();
        if (!s.isEmpty()) {
            char c = s.charAt(0);
            if (Character.isLowerCase(c)) s = Character.toUpperCase(c) + s.substring(1);
        }
        return s;
    }

    // ------------------------------------------------------------------ cleaning (P-05, P-09)

    /** True when the text contains at least one letter or digit, i.e. there is something to pronounce. */
    public static boolean hasSpeakable(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    public static String clean(String text) { return clean(text, new Opts()); }

    /**
     * Removes everything a TTS engine must not see: markdown, code blocks, URLs, emoji and symbols, emotion tags
     * (unless kept), ellipses (a trailing "..." made Edge read for 5-10 s), repeated punctuation. Numbers are
     * not expanded here ({@link #forSpeech}). Returns "" when no letter or digit is left: such a text must not
     * be sent to a provider at all.
     */
    public static String clean(String text, Opts opts) {
        if (opts == null) opts = new Opts();
        if (text == null) return "";
        String s = cleanMarkdown(text, opts.codeMarker);
        s = urlsToLink(s);
        s = stripEmoji(s);
        s = stripSymbols(s);
        s = handleTags(s, opts.keepEmotionTags);
        s = s.replaceAll("[\\s\\u00A0]+", " ").trim();
        s = fixEllipses(s);
        s = collapsePunctuation(s);
        s = s.replaceAll("\\s+([,.!?;:…])", "$1").replaceAll("\\s{2,}", " ")
            .replaceAll("([(«])\\s+", "$1").replaceAll("\\s+([)»])", "$1").trim();
        return hasSpeakable(s) ? s : "";
    }

    private static final Pattern FENCED = Pattern.compile("(?s)```[^\\n]*\\n.*?```");
    private static final Pattern FENCE_OPEN = Pattern.compile("(?s)```[^\\n]*\\n.*$");
    private static final Pattern LIST_LINE = Pattern.compile(
            "(?m)^[ \\t]*(?:#{1,6}[ \\t]+|[-*•‣–][ \\t]+|\\d{1,2}[.)][ \\t]+)(.*?)[ \\t]*$");
    private static final Pattern TABLE_RULE = Pattern.compile("(?m)^[ \\t]*\\|?[ \\t:|-]*-[ \\t:|-]*\\|[ \\t:|-]*$");
    private static final Pattern HRULE = Pattern.compile("(?m)^[ \\t]*(?:-{3,}|\\*{3,}|_{3,}|={3,})[ \\t]*$");

    private static String cleanMarkdown(String s, String codeMarker) {
        String marker = codeMarker == null || codeMarker.trim().isEmpty() ? " " : ". " + codeMarker.trim() + ". ";
        s = FENCED.matcher(s).replaceAll(Matcher.quoteReplacement(marker));
        s = FENCE_OPEN.matcher(s).replaceAll(Matcher.quoteReplacement(marker));     // block cut off by the end of text
        s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ");                        // images: drop entirely
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", " $1 ");                    // [label](url) -> label
        s = s.replaceAll("`+", " ");                                               // inline code marks
        s = HRULE.matcher(s).replaceAll("");
        s = TABLE_RULE.matcher(s).replaceAll("");
        s = s.replaceAll("[ \\t]*\\|[ \\t]*", ", ");                                   // table cells
        s = s.replaceAll("(?m)^[ \\t]*,[ \\t]*|,[ \\t]*$", "");                        // ...without leading/trailing separators
        s = s.replaceAll("\\*\\*|__|~~", " ");                                     // bold/strike markers
        s = s.replaceAll("(?m)^[ \\t]*>[ \\t]?", "");                              // blockquote
        s = s.replaceAll("\\[\\d{1,3}\\]", " ");                                    // citation marks [1]
        Matcher m = LIST_LINE.matcher(s);                                         // headings, bullets, numbered items: end each with a pause
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String body = m.group(1);
            if (!body.isEmpty() && Character.isLetterOrDigit(body.charAt(body.length() - 1))) body += ".";
            m.appendReplacement(sb, Matcher.quoteReplacement(body));
        }
        m.appendTail(sb);
        s = sb.toString();
        s = s.replaceAll("[*_]+", " ");                                            // single-star/underscore emphasis, snake_case
        return s;
    }

    private static String urlsToLink(String s) {
        s = s.replaceAll("(?i)\\b(?:https?://|www\\.)[^\\s<>\"]*[^\\s<>\".,;:!?)\\]»]", " ссылка ");
        s = s.replaceAll("(?i)\\b(?:https?://|www\\.)[^\\s<>\"]*", " ссылка ");
        return s;
    }

    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{2190}-\\x{21FF}\\x{2300}-\\x{23FF}"
                    + "\\x{FE0F}\\x{200D}\\x{20E3}\\x{00A9}\\x{00AE}\\x{2122}]");

    private static String stripEmoji(String s) {
        return EMOJI.matcher(s).replaceAll(" ");
    }

    /** Symbols, control/format characters and leftover markup characters that have no pronunciation. */
    private static String stripSymbols(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            boolean drop;
            switch (Character.getType(cp)) {
                case Character.OTHER_SYMBOL:
                    drop = cp != '°' && cp != '№'; break;
                case Character.MODIFIER_SYMBOL: case Character.FORMAT:
                case Character.PRIVATE_USE: case Character.SURROGATE: case Character.UNASSIGNED:
                    drop = true; break;
                case Character.CONTROL:
                    drop = cp != '\n' && cp != '\t'; break;
                default:
                    drop = cp == '~' || cp == '^' || cp == '|' || cp == '\\' || cp == '{' || cp == '}' || cp == '#'
                        || cp == '•' || cp == '‣' || cp == '◦' || cp == '▪' || cp == '·';
            }
            if (drop) {
                if (sb == null) { sb = new StringBuilder(s.length()); sb.append(s, 0, i); }
                sb.append(' ');
            } else if (sb != null) {
                sb.appendCodePoint(cp);
            }
            i += n;
        }
        return sb == null ? s : sb.toString();
    }

    private static final Pattern ELLIPSIS = Pattern.compile("(?:…|\\.)(?:[ \\t]*(?:…|\\.))+|…");

    /**
     * "..." / "…" / ". . ." become a short comma-like pause inside a sentence and a plain sentence end before a
     * new sentence or at the end of the text; with nothing speakable before them they vanish.
     */
    private static String fixEllipses(String s) {
        if (s.indexOf('…') < 0 && s.indexOf("..") < 0 && !s.contains(". .")) return s;
        Matcher m = ELLIPSIS.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group();
            int dots = 0;
            boolean uni = false;
            for (int i = 0; i < g.length(); i++) { char c = g.charAt(i); if (c == '.') dots++; else if (c == '…') uni = true; }
            if (!uni && dots < 2) continue;
            int from = m.start() - 1;
            while (from >= 0 && s.charAt(from) == ' ') from--;
            char prev = from >= 0 ? s.charAt(from) : 0;
            boolean afterWord = Character.isLetterOrDigit(prev) || prev == ')' || prev == '"' || prev == '»';
            int to = m.end();
            while (to < s.length() && (s.charAt(to) == ' ' || s.charAt(to) == '"' || s.charAt(to) == '»' || s.charAt(to) == ')')) to++;
            String rep;
            if (!afterWord) rep = "";
            else if (to >= s.length()) rep = ".";
            else if (Character.isLowerCase(s.charAt(to))) rep = ",";
            else rep = ".";
            m.appendReplacement(sb, rep);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String collapsePunctuation(String s) {
        s = s.replaceAll("[!?]*\\?[!?]*", "?").replaceAll("!{2,}", "!");          // ?! !!! ??? -> one mark
        s = s.replaceAll("([,;:])(?:\\s*[,;:])+", "$1");                         // ,, ;, : ,
        s = s.replaceAll("[,;:]\\s*([.!?])", "$1");                              // ",." -> "."
        s = s.replaceAll("-{2,}|—{2,}|–{2,}", " — ");
        s = s.replaceAll("(?<=[!?])\\s*\\.", "");                                   // "!." -> "!"
        s = s.replaceAll("^[\\s,;:.!?]+", "").replaceAll("[\\s,;]+$", "");                                 // nothing before the first word
        return s;
    }

    /** [whispers] / [sighs] / [pause] — ASCII-letter tags for ElevenLabs v3/v4. */
    private static final Pattern TAG = Pattern.compile("\\[[a-zA-Z][a-zA-Z ,]{0,29}\\]");
    /** Unclosed leftover of a tag ("[whispers" with no "]" until the end of text) — always dropped. */
    private static final Pattern TAG_LEFTOVER = Pattern.compile("\\[[a-zA-Z][a-zA-Z ,]{0,29}[ \\t]*$");

    private static String handleTags(String s, boolean keep) {
        if (!keep) s = TAG.matcher(s).replaceAll(" ");
        s = TAG_LEFTOVER.matcher(s).replaceAll(" ");
        return s;
    }

    // ------------------------------------------------------------------ pass: dates

    private static final String[] MONTHS_GEN = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря" };

    private static final Pattern DATE = Pattern.compile("(?<![\\d.])(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?![\\d.])");

    private static String convertDates(String s) {
        Matcher m = DATE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int day = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int year = Integer.parseInt(m.group(3));
            String repl = m.group();
            if (day >= 1 && day <= 31 && month >= 1 && month <= 12 && year >= 1000) {
                repl = ordinalPhrase(day, NOM_N) + " " + MONTHS_GEN[month - 1] + " "
                        + yearOrdinalPhrase(year, GEN) + " года";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ pass: times

    private static final Pattern TIME = Pattern.compile("(?<![\\d:])(\\d{1,2}):(\\d{2})(?![\\d:])");

    private static String convertTimes(String s) {
        Matcher m = TIME.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            String repl = m.group();
            if (h <= 23 && min <= 59) {
                repl = numberWords(h, M) + " " + (min < 10 ? "ноль " + numberWords(min, M) : numberWords(min, M));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ pass: "2026 году" → ordinal year

    private static final Pattern YEAR_WORD = Pattern.compile("(?<![\\d.])(\\d{4})[\\u00A0 ]?(году|года)(?![а-яё])");

    private static String convertYearWords(String s) {
        Matcher m = YEAR_WORD.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int year = Integer.parseInt(m.group(1));
            int cs = m.group(2).equals("году") ? PREP_MN : GEN;
            String repl = yearOrdinalPhrase(year, cs) + " " + m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ pass: ordinals ("3-й")

    private static final Pattern ORDINAL = Pattern.compile(
            "(?<![\\d.а-яёa-z–—-])(\\d{1,12})-"
                    + "(ого|его|ому|ему|ыми|ими|ая|ое|ую|ые|ых|их|ой|ей|ым|им|ом|ем|ми|го|му|ю|й|я|е|м|х)"
                    + "(?![а-яёa-z])");

    private static String convertOrdinals(String s) {
        Matcher m = ORDINAL.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            String suffix = m.group(2);
            String repl = m.group();
            if (n <= 999_999_999_999L) {
                int cs = suffixToCase(suffix);
                repl = ordinalPhrase(n, cs);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Maps a written ordinal suffix to (gender, case). Bare "-й" is the common masculine nominative. */
    private static int suffixToCase(String sf) {
        switch (sf) {
            case "я": case "ая": return NOM_F;
            case "е": case "ое": return NOM_N;
            case "го": case "ого": case "его": return GEN;
            case "му": case "ому": case "ему": return DAT_MN;
            case "ю": case "ую": return ACC_F;
            case "м": case "ым": case "им": return INST_M;
            case "ом": case "ем": return PREP_MN;
            case "ми": case "ими": case "ыми": return PL_INST;
            case "х": case "ых": case "их": return PL_GEN;
            case "ой": case "ей": return GEN_F;
            case "ые": return PL_NOM;
            default: return NOM_M; // "й"
        }
    }

    // ------------------------------------------------------------------ pass: numbers + units (the master pass)

    // genders and ordinal cases (declared early: tables below reference them)
    private static final int M = 0, F = 1, N = 2;
    private static final int NOM_M = 0, NOM_F = 1, NOM_N = 2, GEN = 3, GEN_F = 4, DAT_MN = 5,
            ACC_F = 6, INST_M = 7, PREP_MN = 8, PL_NOM = 9, PL_GEN = 10, PL_INST = 11;

    private static final class Unit {
        final String token;
        final String[] forms; // one / few / many
        final int gender;

        Unit(String token, String f1, String f2, String f3, int gender) {
            this.token = token;
            this.forms = new String[]{f1, f2, f3};
            this.gender = gender;
        }
    }

    private static final Unit[] UNITS = {
            new Unit("км/ч", "километр в час", "километра в час", "километров в час", M),
            new Unit("км", "километр", "километра", "километров", M),
            new Unit("кг", "килограмм", "килограмма", "килограммов", M),
            new Unit("см", "сантиметр", "сантиметра", "сантиметров", M),
            new Unit("мм", "миллиметр", "миллиметра", "миллиметров", M),
            new Unit("мин", "минута", "минуты", "минут", F),
            new Unit("сек", "секунда", "секунды", "секунд", F),
            new Unit("ч", "час", "часа", "часов", M),
            new Unit("л", "литр", "литра", "литров", M),
            new Unit("%", "процент", "процента", "процентов", M),
            new Unit("°C", "градус Цельсия", "градуса Цельсия", "градусов Цельсия", M),
            new Unit("°F", "градус Фаренгейта", "градуса Фаренгейта", "градусов Фаренгейта", M),
            new Unit("₽", "рубль", "рубля", "рублей", M),
            new Unit("$", "доллар", "доллара", "долларов", M),
            new Unit("€", "евро", "евро", "евро", M),
    };

    private static final String[] SCALE_TOKENS = {"тыс", "млн", "млрд"};
    private static final String[][] SCALE_FORMS = {
            {"тысяча", "тысячи", "тысяч"},
            {"миллион", "миллиона", "миллионов"},
            {"миллиард", "миллиарда", "миллиардов"},
    };
    private static final int[] SCALE_GENDER = {F, M, M};

    /**
     * One number: optional minus, optional decimal comma, optional scale word, optional unit.
     * Start/end guards keep digit runs adjacent to ".", "-", ":" untouched (ranges, phones, versions).
     */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\d.,:\\-])([–—-])?"
                    + "(\\d{1,3}(?:[\\u00A0 ]\\d{3})+|\\d+)"
                    + "(?:,(\\d{1,4}))?"
                    + "(?:[\\u00A0 ]+(тыс|млн|млрд)\\.?)?"
                    + "(?:[\\u00A0 ]?(км/ч|км|кг|см|мм|мин|сек|ч|л|%|°C|°F|₽|\\$|€))?"
                    + "(?![\\d,:а-яёa-z–—-]|\\.\\d)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static String convertNumbers(String s) {
        Matcher m = NUMBER.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String repl = expandNumber(m, s);
            if (repl == null) continue; // number too large: leave the region untouched
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String expandNumber(Matcher m, String text) {
        boolean neg = m.group(1) != null;
        String digits = m.group(2).replace(" ", "").replace("\u00A0", "");
        String frac = m.group(3);
        String scaleTok = m.group(4);
        String unitTok = m.group(5);
        if (digits.length() > 12) return null; // > 10^12: don't guess, keep as-is

        int scaleIdx = -1;
        if (scaleTok != null) for (int i = 0; i < SCALE_TOKENS.length; i++) {
            if (SCALE_TOKENS[i].equalsIgnoreCase(scaleTok)) { scaleIdx = i; break; }
        }
        Unit unit = null;
        if (unitTok != null) for (Unit u : UNITS) {
            if (u.token.equalsIgnoreCase(unitTok)) { unit = u; break; }
        }

        StringBuilder out = new StringBuilder();
        if (neg) out.append("минус ");

        if (frac != null && isAllZeros(frac)) frac = null;

        if (frac != null) {
            long intPart = Long.parseLong(digits);
            out.append(decimalWords(intPart, frac));
            // after "N целых k десятых" the noun stands in genitive singular: forms[1]
            if (scaleIdx >= 0) out.append(' ').append(SCALE_FORMS[scaleIdx][1]);
            else if (unit != null) out.append(' ').append(unit.forms[1]);
            return out.toString();
        }

        long n = Long.parseLong(digits);
        if (scaleIdx >= 0) {
            out.append(numberWords(n, SCALE_GENDER[scaleIdx]))
               .append(' ').append(SCALE_FORMS[scaleIdx][plural(n)]);
            if (unit != null) out.append(' ').append(unit.forms[2]); // after a scale word: genitive plural
        } else if (unit != null) {
            out.append(numberWords(n, unit.gender)).append(' ').append(unit.forms[plural(n)]);
        } else {
            out.append(bareCardinal(n, text, m.end()));
        }
        return out.toString();
    }

    private static boolean isAllZeros(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != '0') return false;
        return true;
    }

    /** "3,14" → "три целых четырнадцать сотых"; 0 → "пять десятых" (no "ноль целых"). */
    private static String decimalWords(long intPart, String frac) {
        StringBuilder out = new StringBuilder();
        if (intPart > 0) {
            out.append(numberWords(intPart, F)).append(' ')
               .append(plural(intPart) == 0 ? "целая" : "целых").append(' ');
        }
        long f = Long.parseLong(frac);
        String stem;
        switch (frac.length()) {
            case 1: stem = "десят"; break;
            case 2: stem = "сот"; break;
            case 3: stem = "тысячн"; break;
            default: stem = "десятитысячн"; break;
        }
        out.append(numberWords(f, F)).append(' ').append(stem).append(plural(f) == 0 ? "ая" : "ых");
        return out.toString();
    }

    /** Gender for a bare cardinal, from the noun that follows (matters only for 1/2). */
    private static String bareCardinal(long n, String text, int from) {
        long ones = n % 10;
        if (n % 100 >= 11 && n % 100 <= 14 || ones != 1 && ones != 2)
            return numberWords(n, M); // no gendered/ambiguous forms
        String w = nextWord(text, from);
        if (w.isEmpty()) return numberWords(n, M);
        char last = w.charAt(w.length() - 1);
        if (ones == 2) {
            if (last == 'ы' || last == 'и') return numberWords(n, F);   // "две минуты/двери"
            if (last == 'а' || last == 'я') return numberWords(n, M);   // "два часа/письма/дня"
            if (n == 2) return "двух";                                  // "у 2 окон" → genitive plural context
            return numberWords(n, M);                                   // "22 окон" can't be fixed cheaply
        }
        // ones == 1
        if (last == 'а' || last == 'я') return numberWords(n, F);       // "одна минута"
        if (last == 'о' || last == 'е') return numberWords(n, N);       // "одно окно"
        if (last == 'ь') return numberWords(n, FEM_Ь.contains(w) ? F : M);
        return numberWords(n, M);                                       // "один час"
    }

    private static final Set<String> FEM_Ь = new HashSet<>(Arrays.asList(
            "жизнь", "ночь", "мышь", "вещь", "помощь", "дверь", "тень", "цель", "роль", "кровь",
            "соль", "область", "часть", "степень", "четверть", "связь", "память", "очередь", "болезнь", "медь"));

    private static String nextWord(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        int j = i;
        while (j < text.length() && isWordChar(text.charAt(j))) j++;
        if (j <= i) return "";
        String w = text.substring(i, j);
        return Character.isUpperCase(w.charAt(0)) ? w.toLowerCase(Locale.ROOT) : w;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || Character.isDigit(c) || c == 'ё';
    }

    // ------------------------------------------------------------------ number → words

    private static final String[] ONES_M = {"один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] ONES_F = {"одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] ONES_N = {"одно", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] TEENS = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] TENS = {"двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят",
            "семьдесят", "восемьдесят", "девяносто"};
    private static final String[] HUNDREDS = {"сто", "двести", "триста", "четыреста", "пятьсот",
            "шестьсот", "семьсот", "восемьсот", "девятьсот"};
    private static final String[] THOUSAND = {"тысяча", "тысячи", "тысяч"};
    private static final String[] MILLION = {"миллион", "миллиона", "миллионов"};
    private static final String[] MILLIARD = {"миллиард", "миллиарда", "миллиардов"};

    /** 0..999 999 999 999 → words; gender applies to the final group's units digit (один/одна/одно, два/две). */
    private static String numberWords(long n, int gender) {
        if (n == 0) return "ноль";
        StringBuilder out = new StringBuilder();
        group(out, (n / 1_000_000_000L) % 1000, MILLIARD, M);
        group(out, (n / 1_000_000L) % 1000, MILLION, M);
        group(out, (n / 1_000L) % 1000, THOUSAND, F);
        group(out, n % 1000, null, gender);
        return out.toString().trim();
    }

    private static void group(StringBuilder out, long g, String[] scale, int gender) {
        if (g == 0) return;
        if (out.length() > 0) out.append(' ');
        int start = out.length();
        long h = g / 100, rest = g % 100;
        if (h > 0) out.append(HUNDREDS[(int) h - 1]);
        if (rest >= 10 && rest <= 19) {
            if (out.length() > start) out.append(' ');
            out.append(TEENS[(int) rest - 10]);
        } else {
            long t = rest / 10, u = rest % 10;
            if (t > 0) {
                if (out.length() > start) out.append(' ');
                out.append(TENS[(int) t - 2]);
            }
            if (u > 0) {
                if (out.length() > start) out.append(' ');
                switch (gender) {
                    case F: out.append(ONES_F[(int) u - 1]); break;
                    case N: out.append(ONES_N[(int) u - 1]); break;
                    default: out.append(ONES_M[(int) u - 1]); break;
                }
            }
        }
        if (scale != null) out.append(' ').append(scale[plural(g)]);
    }

    /** Russian paucal rule → 0 = one (1, 21, 101…), 1 = few (2-4, 22-24…), 2 = many (0, 5-20, 11-14…). */
    private static int plural(long n) {
        long m = n % 100;
        if (m >= 11 && m <= 14) return 2;
        long d = n % 10;
        if (d == 1) return 0;
        if (d >= 2 && d <= 4) return 1;
        return 2;
    }

    // ------------------------------------------------------------------ ordinals

    private static final String[] ORD_UNITS = {"первый", "второй", "третий", "четвёртый", "пятый",
            "шестой", "седьмой", "восьмой", "девятый"};
    private static final String[] ORD_TEENS = {"десятый", "одиннадцатый", "двенадцатый", "тринадцатый",
            "четырнадцатый", "пятнадцатый", "шестнадцатый", "семнадцатый", "восемнадцатый", "девятнадцатый"};
    private static final String[] ORD_TENS = {"двадцатый", "тридцатый", "сороковой", "пятидесятый",
            "шестидесятый", "семидесятый", "восьмидесятый", "девяностый"};
    private static final String[] ORD_HUNDREDS = {"сотый", "двухсотый", "трёхсотый", "четырёхсотый", "пятисотый",
            "шестисотый", "семисотый", "восьмисотый", "девятисотый"};
    private static final String[] ORD_THOUSANDS = {"тысячный", "двухтысячный", "трёхтысячный", "четырёхтысячный",
            "пятитысячный", "шеститысячный", "семитысячный", "восьмитысячный", "девятитысячный"};

    /** Masculine nominative ordinal ("двадцать восьмой"); only the last word is an ordinal. */
    private static String ordinalWord(long n) {
        if (n == 0) return "нулевой";
        if (n <= 9) return ORD_UNITS[(int) n - 1];
        if (n <= 19) return ORD_TEENS[(int) n - 10];
        if (n < 100) {
            if (n % 10 == 0) return ORD_TENS[(int) n / 10 - 2];
            return TENS[(int) n / 10 - 2] + " " + ORD_UNITS[(int) n % 10 - 1];
        }
        if (n < 1000) {
            if (n % 100 == 0) return ORD_HUNDREDS[(int) n / 100 - 1];
            return HUNDREDS[(int) n / 100 - 1] + " " + ordinalWord(n % 100);
        }
        if (n < 10_000) {
            if (n % 1000 == 0) return ORD_THOUSANDS[(int) n / 1000 - 1];
            return numberWords((n / 1000) * 1000, M) + " " + ordinalWord(n % 1000);
        }
        if (n == 10_000) return "десятитысячный";
        if (n == 100_000) return "стотысячный";
        if (n == 1_000_000) return "миллионный";
        if (n == 1_000_000_000L) return "миллиардный";
        if (n % 10 != 0) return numberWords(n - n % 10, M) + " " + ordinalWord(n % 10);
        if (n % 100 != 0) return numberWords(n - n % 100, M) + " " + ordinalWord(n % 100);
        if (n % 1000 != 0) return numberWords(n - n % 1000, M) + " " + ordinalWord(n % 1000);
        return numberWords(n, M) + "-й"; // last resort for huge round numbers
    }

    /** Ordinal phrase with the last word declined ("двадцать восьмого"). */
    private static String ordinalPhrase(long n, int cs) {
        String masc = ordinalWord(n);
        int sp = masc.lastIndexOf(' ');
        String head = sp < 0 ? "" : masc.substring(0, sp + 1);
        return head + decline(masc.substring(sp + 1), cs);
    }

    private static String yearOrdinalPhrase(int year, int cs) {
        return ordinalPhrase(year, cs);
    }

    /** Declines the masculine ordinal form like a hard/soft adjective ("пятый"→"пятого", "третий"→"третьего"). */
    private static String decline(String masc, int cs) {
        boolean soft = masc.endsWith("ий");
        String stem = masc.substring(0, masc.length() - 2); // drop the 2-char ending ый/ой/ий
        if (soft) stem = stem + "ь";
        if (cs == NOM_M) return masc;
        String end;
        switch (cs) {
            case NOM_F: end = soft ? "я" : "ая"; break;
            case NOM_N: end = soft ? "е" : "ое"; break;
            case GEN: end = soft ? "его" : "ого"; break;
            case GEN_F: end = soft ? "ей" : "ой"; break;
            case DAT_MN: end = soft ? "ему" : "ому"; break;
            case ACC_F: end = soft ? "ю" : "ую"; break;
            case INST_M: end = soft ? "им" : "ым"; break;
            case PREP_MN: end = soft ? "ем" : "ом"; break;
            case PL_NOM: end = soft ? "ие" : "ые"; break;
            case PL_GEN: end = soft ? "их" : "ых"; break;
            case PL_INST: end = soft ? "ими" : "ыми"; break;
            default: end = "ого"; break;
        }
        return stem + end;
    }

    // ------------------------------------------------------------------ abbreviations

    private static String expandAbbrevs(String s) {
        String[][] pairs = {
                {"т\\.?\\s?е\\.", "то есть"},
                {"т\\.?\\s?д\\.", "так далее"},
                {"т\\.?\\s?п\\.", "тому подобное"},
                {"т\\.?\\s?к\\.", "так как"},
                {"т\\.?\\s?н\\.", "так называемый"},
                {"т\\.?\\s?о\\.", "таким образом"},
        };
        for (String[] p : pairs) {
            s = s.replaceAll("(?iu)(?<![а-яёa-z])" + p[0] + "(?![а-яёa-z])", p[1]);
        }
        return s;
    }

    // ------------------------------------------------------------------ sentence splitting

    private static final Set<String> ABBR_NO_SPLIT = new HashSet<>(Arrays.asList(
            "ул", "кв", "др", "пр", "руб", "тыс", "млн", "млрд", "стр", "проф", "акад", "тов", "э"));

    private static boolean isTerminator(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    /**
     * Splits text into speakable chunks. Sentence boundaries are conservative: the next sentence must
     * start with an uppercase letter or digit, and dots after abbreviations or single initials never
     * split. The first chunk is kept short (≤ min(maxChars, 120)) so playback can start quickly;
     * later chunks pack sentences up to maxChars. Over-long sentences break at the last clause
     * boundary (comma/semicolon/colon/space) inside the limit.
     */
    public static List<String> splitSentences(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null) return chunks;
        text = text.replaceAll("[\\s\\u00A0]+", " ").trim();
        if (text.isEmpty()) return chunks;
        if (maxChars < 20) maxChars = 20;

        List<String> sentences = splitIntoSentences(text);
        int firstLimit = Math.min(maxChars, 120);
        int limit = firstLimit;
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (cur.length() == 0 && s.length() > limit) {
                List<String> pieces = breakLong(s, limit);
                chunks.add(pieces.get(0));
                limit = maxChars;
                // re-pack the remainder of the long first sentence against the normal limit
                StringBuilder rest = new StringBuilder();
                for (int i = 1; i < pieces.size(); i++) {
                    String piece = pieces.get(i);
                    if (rest.length() == 0) { rest.append(piece); continue; }
                    if (rest.length() + 1 + piece.length() <= maxChars) rest.append(' ').append(piece);
                    else { chunks.add(rest.toString()); rest.setLength(0); rest.append(piece); }
                }
                if (rest.length() > 0) cur.append(rest);
                continue;
            }
            if (cur.length() == 0) {
                cur.append(s);
            } else if (cur.length() + 1 + s.length() <= limit) {
                cur.append(' ').append(s);
            } else {
                chunks.add(cur.toString());
                cur.setLength(0);
                cur.append(s);
                limit = maxChars;
            }
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks;
    }

    private static List<String> splitIntoSentences(String text) {
        List<String> out = new ArrayList<>();
        int n = text.length(), start = 0, i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (isTerminator(c)) {
                int j = i;
                while (j < n && isTerminator(text.charAt(j))) j++;
                if (isSentenceBoundary(text, i, j)) {
                    out.add(text.substring(start, j).trim());
                    start = j;
                }
                i = j;
            } else {
                i++;
            }
        }
        if (start < n) {
            String tail = text.substring(start).trim();
            if (!tail.isEmpty()) out.add(tail);
        }
        return out;
    }

    private static boolean isSentenceBoundary(String text, int i, int j) {
        int n = text.length();
        char dot = text.charAt(i);
        if (dot == '.' && i > 0 && Character.isDigit(text.charAt(i - 1)) && j < n && Character.isDigit(text.charAt(j)))
            return false; // decimal like 3.5
        if (j < n) {
            int k = j;
            while (k < n && (Character.isWhitespace(text.charAt(k)) || text.charAt(k) == '«' || text.charAt(k) == '"'
                    || text.charAt(k) == '\'' || text.charAt(k) == '(' || text.charAt(k) == '[')) k++;
            if (k >= n) return true;
            char nx = text.charAt(k);
            if (!(Character.isUpperCase(nx) || Character.isDigit(nx))) return false;
        }
        // word immediately before the terminator
        int b = i;
        while (b > 0 && Character.isLetter(text.charAt(b - 1))) b--;
        String word = text.substring(b, i).toLowerCase(Locale.ROOT);
        if (word.length() == 1) return false; // initials "А. С. Пушкин", "г."
        return !ABBR_NO_SPLIT.contains(word);
    }

    private static List<String> breakLong(String s, int limit) {
        List<String> out = new ArrayList<>();
        String rest = s.trim();
        while (rest.length() > limit) {
            int cut = -1;
            for (int i = Math.min(limit, rest.length() - 1); i > 0; i--) {
                char c = rest.charAt(i);
                if (c == ',' || c == ';' || c == ':' || c == ' ') { cut = i; break; }
            }
            if (cut <= 0) cut = limit;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        if (out.isEmpty()) out.add(s);
        return out;
    }
}
