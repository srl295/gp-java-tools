/*
 * Copyright IBM Corp. 2015, 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ibm.g11n.pipeline.resfilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import com.ibm.g11n.pipeline.resfilter.ResourceString.ResourceStringComparator;

/**
 * Java properties resource filter implementation.
 *
 * @author Yoshito Umaoka
 */
public class JavaPropertiesResource implements ResourceFilter {

    // TODO:
    // This is not a good idea. This implementation might work,
    // but it depends on an assumption that
    // java.util.Properties#load(InputStream)
    // calls Properties#put(Object, Object).

    @SuppressWarnings("serial")
    public class LinkedProperties extends Properties {
        private final HashSet<Object> keys = new LinkedHashSet<Object>();

        public LinkedProperties() {
        }

        public Iterable<Object> orderedKeys() {
            return Collections.list(keys());
        }

        @Override
        public Enumeration<Object> keys() {
            return Collections.<Object> enumeration(keys);
        }

        @Override
        public Object put(Object key, Object value) {
            keys.add(key);
            return super.put(key, value);
        }
    }

    @Override
    public Bundle parse(InputStream inStream) throws IOException {
        LinkedProperties props = new LinkedProperties();
        BufferedReader inStreamReader = new BufferedReader(new InputStreamReader(inStream, PROPS_ENC));
        String line;
        Map<String, List<String>> notesMap = new HashMap<>();
        List<String> currentNotes = new ArrayList<>();
        boolean globalNotesAvailable = true;
        List<String> globalNotes = null;
        while ((line = inStreamReader.readLine()) != null) {
            line = stripLeadingSpaces(line);
            // Comment line - Add to list of comments (notes) until we find
            // either
            // a blank line (global comment) or a key/value pair
            if (line.startsWith("#") || line.startsWith("!")) {
                // Strip off the leading comment marker, and perform any
                // necessary unescaping here.
                currentNotes.add(unescape(line.substring(1)));
            } else if (line.isEmpty()) {
                // We are following the convention that the first blank line in
                // a properties
                // file signifies the end of a global comment.
                if (globalNotesAvailable && !currentNotes.isEmpty()) {
                    globalNotes = new ArrayList<>(currentNotes);
                    currentNotes.clear();
                } else {
                    // Just a generic blank line - treat it like a comment.
                    currentNotes.add(line);
                }
                globalNotesAvailable = false;
            } else {
                // Regular non-comment line. If there are notes outstanding that
                // apply
                // to this line, we find its key and add it to the notes map.
                StringBuffer sb = new StringBuffer(line);
                while (isContinuationLine(sb.toString())) {
                    String continuationLine = inStreamReader.readLine();
                    sb.setLength(sb.length() - 1); // Remove the continuation
                                                   // "\"
                    if (continuationLine != null) {
                        sb.append(stripLeadingSpaces(continuationLine));
                    }
                }
                String logicalLine = sb.toString();
                PropDef pd = PropDef.parseLine(logicalLine);
                props.setProperty(pd.getKey(), pd.getValue());
                if (!currentNotes.isEmpty()) {
                    notesMap.put(pd.getKey(), new ArrayList<>(currentNotes));
                    currentNotes.clear();
                }
            }
        }

        Iterator<Object> i = props.orderedKeys().iterator();
        Bundle result = new Bundle();
        int sequenceNum = 0;
        while (i.hasNext()) {
            String key = (String) i.next();
            List<String> notes = notesMap.get(key);
            ResourceString rs = new ResourceString(key, props.getProperty(key), ++sequenceNum, notes);
            result.addResourceString(rs);
        }
        if (globalNotes != null) {
            result.addNotes(globalNotes);
        }
        return result;
    }

    // This method handles the bizarre edge case where someone might have
    // multiple backslashes at the end of a line.  An even number of them
    // isn't really a continuation, but a backslash in the property value.
    private boolean isContinuationLine(String s) {
        int backslashCount = 0;
        for (int index = s.length() - 1; index >= 0; index--) {
            if (s.charAt(index) != '\\') {
                break;
            }
            backslashCount++;
        }
        return backslashCount % 2 == 1;
    }

    @Override
    public void write(OutputStream outStream, String language, Bundle resource) throws IOException {
        TreeSet<ResourceString> sortedResources = new TreeSet<>(new ResourceStringComparator());
        sortedResources.addAll(resource.getResourceStrings());

        LinkedProperties props = new LinkedProperties();
        for (ResourceString res : sortedResources) {
            props.setProperty(res.getKey(), res.getValue());
        }
        props.store(outStream, null);
    }

    private static final String PROPS_ENC = "ISO-8859-1";

    static class PropDef {
        private String key;
        private String value;
        private PropSeparator separator;

        public enum PropSeparator {
            EQUAL('='), COLON(':'), SPACE(' ');

            private char sepChar;

            private PropSeparator(char sepChar) {
                this.sepChar = sepChar;
            }

            public char getCharacter() {
                return sepChar;
            }
        }

        private static final String INDENT = "    ";
        private static final int COLMAX = 80;

        public PropDef(String key, String value, PropSeparator separator) {
            this.key = key;
            this.value = value;
            this.separator = separator;
        };

        public static PropDef parseLine(String line) {
            PropSeparator sep = null;
            int sepIdx = -1;

            boolean sawSpace = false;
            for (int i = 0; i < line.length(); i++) {
                char iChar = line.charAt(i);

                if (sawSpace) {
                    if (iChar == PropSeparator.EQUAL.getCharacter()) {
                        sep = PropSeparator.EQUAL;
                    } else if (iChar == PropSeparator.COLON.getCharacter()) {
                        sep = PropSeparator.COLON;
                    } else {
                        sep = PropSeparator.SPACE;
                    }
                } else {
                    if (i > 0 && line.charAt(i - 1) != '\\') {
                        if (iChar == ' ') {
                            sawSpace = true;
                        } else if (iChar == PropSeparator.EQUAL.getCharacter()) {
                            sep = PropSeparator.EQUAL;
                        } else if (iChar == PropSeparator.COLON.getCharacter()) {
                            sep = PropSeparator.COLON;
                        }
                    }
                }

                if (sep != null) {
                    sepIdx = i;
                    break;
                }
            }

            if (sepIdx <= 0 || sep == null) {
                return null;
            }

            String key = unescapePropKey(line.substring(0, sepIdx).trim());
            String value = unescapePropValue(stripLeadingSpaces(line.substring(sepIdx + 1)));

            PropDef pl = new PropDef(key, value, sep);
            return pl;
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

        public PropSeparator getSeparator() {
            return separator;
        }

        public void print(PrintWriter pw, String language) throws IOException {
            StringBuilder buf = new StringBuilder(100);
            int len = key.length() + value.length()
                    + 3; /* 3 - length of separator plus two SPs */

            if (len <= COLMAX) {
                // Print this property in a single line
                if (separator.getCharacter() == PropSeparator.SPACE.getCharacter()) {
                    buf.append(escapePropKey(key)).append(separator.getCharacter());
                } else {
                    buf.append(escapePropKey(key)).append(' ').append(separator.getCharacter()).append(' ');
                }
                buf.append(escapePropValue(value));
                pw.println(buf.toString());
                return;
            }

            // prints out in multiple lines

            // always prints out key and separator in a single line
            if (separator.getCharacter() == PropSeparator.SPACE.getCharacter()) {
                buf.append(escapePropKey(key)).append(separator.getCharacter());
            } else {
                buf.append(escapePropKey(key)).append(' ').append(separator.getCharacter()).append(' ');
            }

            if (buf.length() > COLMAX) {
                buf.append('\\');
                pw.println(buf.toString());

                // clear the buffer and indent
                buf.setLength(0);
                buf.append(INDENT);
            }

            BreakIterator brk = BreakIterator.getWordInstance(Locale.forLanguageTag(language));
            brk.setText(value);

            int start = 0;
            int end = brk.next();
            boolean emitNext = false;
            boolean firstSegment = true;
            while (end != BreakIterator.DONE) {
                String segment = value.substring(start, end);
                String escSegment = null;
                if (firstSegment) {
                    escSegment = escape(segment, EscapeSpace.LEADING_ONLY);
                    firstSegment = false;
                } else {
                    escSegment = escape(segment, EscapeSpace.NONE);
                }
                if (emitNext || (buf.length() + escSegment.length() + 2 >= COLMAX)) {
                    // First character in a continuation line must be
                    // a non-space character. Otherwise, keep appending
                    // segments to the current line.
                    if (!isPropsWhiteSpaceChar(escSegment.charAt(0))) {
                        // This segment is safe as the first word
                        // of a continuation line.
                        buf.append('\\');
                        pw.println(buf.toString());

                        // clear the buffer and indent
                        buf.setLength(0);
                        buf.append(INDENT);
                        emitNext = false;
                    }
                }
                buf.append(escSegment);
                if (buf.length() + 2 >= COLMAX) {
                    // defer to emit the line after checking
                    // the next segment.
                    emitNext = true;
                }
                start = end;
                end = brk.next();
            }
            // emit the last line
            if (buf.length() > 0) {
                pw.println(buf.toString());
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (obj.getClass() != PropDef.class)
                return false;
            PropDef p = (PropDef) obj;
            return getKey().equals(p.getKey()) && getValue().equals(p.getValue())
                    && getSeparator().getCharacter() == p.getSeparator().getCharacter();
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("Key=");
            builder.append(getKey());
            builder.append(" Value=");
            builder.append(getValue());
            builder.append(" Sep=");
            builder.append("'" + getSeparator().getCharacter() + "'");
            return builder.toString();
        }
    }

    private static final char BACKSLASH = '\\';

    private enum EscapeSpace {
        ALL,
        LEADING_ONLY,
        NONE;
    }

    private static String escape(String str, EscapeSpace escSpace) {
        StringBuilder buf = new StringBuilder();
        int idx = 0;

        // Handle leading space characters
        if (escSpace == EscapeSpace.ALL || escSpace == EscapeSpace.LEADING_ONLY) {
            // Java properties specification considers the characters space (' ', '\u0020'),
            // tab ('\t', '\u0009'), and form feed ('\f', '\u000C') to be white space. 
            // 
            // java.util.Properties#store() implementation escapes space characters
            // to "\ " in key string, as well as leading spaces in value string.
            // Other white space characters are encoded by Unicode escape sequence.
            for (; idx < str.length(); idx++) {
                char c = str.charAt(idx);
                if (c == ' ') {
                    buf.append(BACKSLASH).append(' ');
                } else if (c == '\t') {
                    buf.append(BACKSLASH).append('t');
                } else if (c == '\f') {
                    buf.append(BACKSLASH).append('f');
                } else {
                    break;
                }
            }
        }

        for (int i = idx; i < str.length(); i++) {
            char c = str.charAt(i);

            if (c < 0x20 || c >= 0x7E) {
                // JDK API comment for Properties#store() specifies below:
                //
                // Characters less than \\u0020 and characters greater than \u007E in property keys
                // or values are written as \\uxxxx for the appropriate hexadecimal value xxxx.
                //
                // However, actual implementation uses "\t" for horizontal tab, "\n" for newline
                // and so on. This implementation support the equivalent behavior.
                switch (c) {
                case '\t':
                    buf.append(BACKSLASH).append('t');
                    break;
                case '\n':
                    buf.append(BACKSLASH).append('n');
                    break;
                case '\f':
                    buf.append(BACKSLASH).append('f');
                    break;
                case '\r':
                    buf.append(BACKSLASH).append('r');
                    break;
                default:
                    appendUnicodeEscape(buf, c);
                    break;
                }
            } else {
                switch (c) {
                case ' ':   // space
                    if (escSpace == EscapeSpace.ALL) {
                        buf.append(BACKSLASH).append(c);
                    } else {
                        buf.append(c);
                    }
                    break;

                // The key and element characters #, !, =, and : are written with
                // a preceding backslash
                case '#':
                case '!':
                case '=':
                case ':':
                case '\\':
                    buf.append(BACKSLASH).append(c);
                    break;

                default:
                    buf.append(c);
                    break;
                }
            }
        }

        return buf.toString();
    }

    static String escapePropKey(String str) {
        return escape(str, EscapeSpace.ALL);
    }

    static String escapePropValue(String str) {
        return escape(str, EscapeSpace.LEADING_ONLY);
    }

    static void appendUnicodeEscape(StringBuilder buf, char codeUnit) {
        buf.append(BACKSLASH).append('u')
        .append(String.format("%04X", (int)codeUnit));
    }

    static String unescapePropKey(String str) {
        return unescape(str);
    }

    static String unescapePropValue(String str) {
        return unescape(str);
    }

    private static String unescape(String str) {
        StringBuilder buf = new StringBuilder();
        boolean isEscSeq = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (isEscSeq) {
                switch (c) {
                case 't':
                    buf.append('\t');
                    break;

                case 'n':
                    buf.append('\n');
                    break;

                case 'f':
                    buf.append('\f');
                    break;

                case 'r':
                    buf.append('\r');
                    break;

                case 'u':
                {
                    // This implementation throws an IllegalArgumentException
                    // when the input string contains a malformed Unicode escape
                    // character sequence. This behavior matches java.util.Properties#load(Reader).
                    final String errMsg = "Malformed \\uxxxx encoding.";
                    if (i + 4 > str.length()) {
                        throw new IllegalArgumentException(errMsg);
                    }
                    // Parse hex digits
                    String hexDigits = str.substring(i + 1, i + 5);
                    try {
                        char codeUnit = (char)Integer.parseInt(hexDigits, 16);
                        buf.append(Character.valueOf(codeUnit));
                        i += 4;
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(errMsg, e);
                    }
                    break;
                }

                default:
                    // Special rules applied to Java properties format
                    // beyond standard Java escape character sequence.
                    //
                    // 1. Octal escapes are not recognized
                    // 2. \b does not represent a backspace character
                    // 3. Backslash is dropped from unrecognized escape sequence.
                    //    For example, "\z" is interpreted as a single character 'z'.

                    buf.append(c);
                    break;
                }
                isEscSeq = false;
            } else {
                if (c == BACKSLASH) {
                    isEscSeq = true;
                } else {
                    buf.append(c);
                }
            }
        }

        // Note: Incomplete escape sequence should not be there.
        // This implementation silently drop the character for the case.

        return buf.toString();
    }

    @Override
    public void merge(InputStream base, OutputStream outStream, String language, Bundle resource) throws IOException {
        Map<String, String> resMap = new HashMap<String, String>(resource.getResourceStrings().size() * 4 / 3 + 1);
        for (ResourceString res : resource.getResourceStrings()) {
            resMap.put(res.getKey(), res.getValue());
        }

        BufferedReader baseReader = new BufferedReader(new InputStreamReader(base, PROPS_ENC));
        PrintWriter outWriter = new PrintWriter(new OutputStreamWriter(outStream, PROPS_ENC));

        String line = null;
        StringBuilder logicalLineBuf = new StringBuilder();
        List<String> orgLines = new ArrayList<String>(8); // default size - up
                                                          // to 8 continuous
                                                          // lines
        do {
            // logical line that may define a single property, or empty line
            String logicalLine = null;

            line = baseReader.readLine();

            if (line == null) {
                // End of the file - emit lines not yet processed.
                if (!orgLines.isEmpty()) {
                    logicalLine = logicalLineBuf.toString();
                }
            } else {
                String normLine = stripLeadingSpaces(line);

                if (orgLines.isEmpty()) {
                    // No continuation marker in the previous line
                    if (normLine.startsWith("#") || normLine.startsWith("!")) {
                        // Comment line - print the original line
                        outWriter.println(line);
                    } else if (isContinuationLine(normLine)) {
                        // Continue to the next line
                        logicalLineBuf.append(normLine, 0, normLine.length() - 1);
                        orgLines.add(line);
                    } else {
                        logicalLine = line;
                    }
                } else {
                    // Continued from the previous line
                    if (normLine.endsWith("\\")) {
                        // continues to the next line
                        logicalLineBuf.append(normLine.substring(0, normLine.length() - 1));
                        orgLines.add(line); // preserve the original line
                    } else {
                        // terminating the current logical property line
                        logicalLineBuf.append(normLine);
                        orgLines.add(line);
                        logicalLine = logicalLineBuf.toString();
                    }
                }
            }

            if (logicalLine != null) {
                PropDef pd = PropDef.parseLine(logicalLine);
                if (pd != null && resMap.containsKey(pd.getKey())) {
                    // Preserve original leading spaces
                    String firstLine = orgLines.isEmpty() ? line : orgLines.get(0);
                    int len = getLeadingSpacesLength(firstLine);
                    if (len > 0) {
                        outWriter.print(firstLine.substring(0, len));
                    }
                    // Write the property key and value
                    String key = pd.getKey();
                    PropDef modPd = new PropDef(key, resMap.get(key), pd.getSeparator());
                    modPd.print(outWriter, language);
                } else {
                    if (orgLines.isEmpty()) {
                        // Single line
                        outWriter.println(line);
                    } else {
                        // Multiple lines
                        for (String orgLine : orgLines) {
                            outWriter.println(orgLine);
                        }
                    }
                }

                // Clear continuation data
                orgLines.clear();
                logicalLineBuf.setLength(0);
            }
        } while (line != null);

        outWriter.flush();
    }

    private static int getLeadingSpacesLength(String s) {
        int idx = 0;
        for (; idx < s.length(); idx++) {
            if (!isPropsWhiteSpaceChar(s.charAt(idx))) {
                break;
            }
        }
        return idx;
    }

    private static String stripLeadingSpaces(String s) {
        return s.substring(getLeadingSpacesLength(s));
    }

    private static boolean isPropsWhiteSpaceChar(char c) {
        // Java properties specification considers the characters space (' ', '\u0020'),
        // tab ('\t', '\u0009'), and form feed ('\f', '\u000C') to be white space. 

        return c == ' ' || c == '\t' || c == '\f'; 
    }
}
