package io.apigee.trireme.rhino.compiler;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This file processes really simple macros by just removing them. (We can do more later if we need.)
 * It takes a file that contains names of macros, one per line. It turns them into the pattern:
 *     MACRO(x);
 * Where "x" may be zero or more characters.
 * Portions of any line that match the pattern are removed.
 */

public class MacroProcessor
{
    private final ArrayList<Pattern> patterns = new ArrayList<Pattern>();

    public MacroProcessor(String macroFile)
        throws IOException
    {
        BufferedReader rdr =
            new BufferedReader(new FileReader(macroFile));

        String line;
        do {
            line = rdr.readLine();
            if (line != null) {
                String tline = line.trim();
                if (!tline.isEmpty()) {
                    Pattern p = Pattern.compile(tline + "\\([^)]*\\)\\;");
                    patterns.add(p);
                }
            }
        } while (line != null);
    }

    public String processLine(String l)
    {
        String line = l;
        for (Pattern pat : patterns) {
            while (true) {
                // Loop here to catch nested macros.
                Matcher m = pat.matcher(line);
                if (m.find()) {
                    line = m.replaceFirst("");
                } else {
                    break;
                }
            }
        }
        return line;
    }
}
