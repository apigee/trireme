/**
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.rhino.compiler;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.DirectoryScanner;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.optimizer.ClassCompiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * Compile the JavaScript source in a given directory to .class files that may be loaded and executed as
 * instances of the Script class.
 */

@Mojo(name="compile")
public class RhinoCompiler
    extends AbstractMojo
{
    public static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * The base directory for JavaScript sources. Defaults to src/main/javascript.
     */
    @Parameter(defaultValue = "${basedir}/src/main/javascript")
    private String directory = "${basedir}/src/main/javascript";

    /**
     * The target directory for the .class files. Defaults to target/classes.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private String targetPath = "${project.build.outputDirectory}";

    /**
     * The pattern that matches files underneath the target (which is normally src/main/javascript).
     */
    @Parameter(defaultValue = "**/*.js")
    private String pattern = "**/*.js";

    @Parameter
    private String codePrefix;

    @Parameter
    private String codePostfix;

    @Parameter
    private boolean generateSource = true;

    @Parameter
    private int optimizationLevel = 1;

    @Parameter
    private boolean debugInfo;

    @Parameter
    private boolean generateObserverCount;

    @Parameter
    private String macroFile;

    private CompilerEnvirons createEnvironment()
    {
        // Since this is only used in our own project, we hard-code these. A "real" plugin would
        // have them all configurable
        CompilerEnvirons env = new CompilerEnvirons();
        env.setLanguageVersion(Context.VERSION_1_8);
        env.setGenerateDebugInfo(debugInfo);
        env.setOptimizationLevel(optimizationLevel);
        env.setGeneratingSource(generateSource);
        env.setRecordingComments(false);
        env.setRecoverFromErrors(false);
        env.setGenerateObserverCount(generateObserverCount);
        return env;
    }

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        // Be prepared to remove some macros from the source before compiling
        MacroProcessor macro = null;
        if (macroFile != null) {
            try {
                macro = new MacroProcessor(macroFile);
            } catch (IOException e) {
                throw new MojoFailureException(e.toString());
            }
        }

        Log log = getLog();
        File baseDir = new File(directory);
        if (baseDir.exists() && baseDir.isDirectory()) {
            CompilerEnvirons env = createEnvironment();
            ClassCompiler compiler = new ClassCompiler(env);
            File targetDirFile = new File(targetPath);

            DirectoryScanner scanner = new DirectoryScanner();
            scanner.setBasedir(baseDir);
            scanner.setIncludes(new String[]{pattern});
            scanner.scan();

            for (String fn : scanner.getIncludedFiles()) {
                File input = new File(baseDir, fn);
                String baseName = getBaseName(fn);
                String className = baseName.replaceAll("[/\\\\]", ".");
                File output = new File(targetDirFile, baseName + ".class");
                File srcOutput = new File(targetDirFile, baseName + ".js");

                if (input.lastModified() >= output.lastModified()) {
                    if (output.getParentFile() != null) {
                        output.getParentFile().mkdirs();
                    }

                    log.info("Compiling " + fn + " to " + output.getPath());

                    try {
                        String source = loadSource(input, macro);

                        Object[] bytes;
                        try {
                            bytes = compiler.compileToClassFiles(addPrefixes(source), input.getName(),
                                                                 1, className);
                        } catch (RhinoException re) {
                            throw new MojoExecutionException(
                                "Error compiling script file " + fn + " :" + re.lineNumber() + ':' + re, re);
                        }
                        if (bytes.length > 2) {
                            throw new MojoExecutionException("Compiler produced more than one class, which was not expected");
                        }

                        writeFromArray((byte[])bytes[1], output);
                        writeFromString(source, srcOutput);
                    } catch (IOException ioe) {
                        throw new MojoExecutionException("Error reading or writing file: " + ioe, ioe);
                    }
                }
            }
        } else {
            log.info("Ignoring non-existent directory " + baseDir.getPath());
        }
    }

    private String getBaseName(String fn)
    {
        int lastDot = fn.lastIndexOf('.');
        if (lastDot > 0) {
            return (fn.substring(0, lastDot));
        }
        return fn;
    }

    private String loadSource(File in, MacroProcessor macro)
        throws IOException
    {
        StringBuilder str = new StringBuilder();
        BufferedReader rdr =
            new BufferedReader(new FileReader(in));
        String line;

        try {
            do {
                line = rdr.readLine();
                if (line != null) {
                    if (macro != null) {
                        line = macro.processLine(line);
                    }
                    str.append(line).append('\n');
                }
            } while (line != null);

            return str.toString();
        } finally {
            rdr.close();
        }
    }

    private String addPrefixes(String s)
    {
        StringBuilder p = new StringBuilder(s.length());
        if (codePrefix != null) {
            p.append(codePrefix);
        }
        p.append(s);
        if (codePostfix != null) {
            p.append(codePostfix);
        }
        return p.toString();
    }

    private void writeFromArray(byte[] bytes, File out)
        throws IOException
    {
        if (out.exists()) {
            out.delete();
        }

        FileOutputStream of = new FileOutputStream(out);
        try {
            of.write(bytes);
        } finally {
            of.close();
        }
    }

    private void writeFromString(String str, File out)
        throws IOException
    {
        if (out.exists()) {
            out.delete();
        }

        FileOutputStream of = new FileOutputStream(out);
        OutputStreamWriter ofr = new OutputStreamWriter(of);
        try {
            ofr.write(str);
        } finally {
            ofr.close();
        }
    }
}
