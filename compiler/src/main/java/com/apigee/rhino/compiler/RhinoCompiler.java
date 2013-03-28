package com.apigee.rhino.compiler;

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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Compile the JavaScript source in a given directory to .class files that may be loaded and executed as
 * instances of the Script class.
 */

@Mojo(name="compile")
public class RhinoCompiler
    extends AbstractMojo
{
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

    @Parameter
    private String codePrefix;

    @Parameter
    private String codePostfix;

    @Parameter
    private boolean generateSource;

    @Parameter
    private int optimizationLevel = 1;

    @Parameter
    private boolean debugInfo;

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
        env.setGenerateObserverCount(false);
        return env;
    }

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        Log log = getLog();
        File baseDir = new File(directory);
        if (baseDir.exists() && baseDir.isDirectory()) {
            CompilerEnvirons env = createEnvironment();
            ClassCompiler compiler = new ClassCompiler(env);
            File targetDirFile = new File(targetPath);

            DirectoryScanner scanner = new DirectoryScanner();
            scanner.setBasedir(baseDir);
            scanner.setIncludes(new String[]{"**/*.js"});
            scanner.scan();

            for (String fn : scanner.getIncludedFiles()) {
                File input = new File(baseDir, fn);
                String baseName = getBaseName(fn);
                String className = baseName.replace("/", ".");
                File output = new File(targetDirFile, baseName + ".class");

                if (input.lastModified() >= output.lastModified()) {
                    if (output.getParentFile() != null) {
                        output.getParentFile().mkdirs();
                    }

                    log.info("Compiling " + fn + " to " + output.getPath());

                    try {
                        Object[] bytes;
                        try {
                            bytes = compiler.compileToClassFiles(loadSource(input), input.getPath(),
                                                                 1, className);
                        } catch (RhinoException re) {
                            throw new MojoExecutionException(
                                "Error compiling script file " + fn + " :" + re.lineNumber() + ':' + re, re);
                        }
                        if (bytes.length > 2) {
                            throw new MojoExecutionException("Compiler produced more than one class, which was not expected");
                        }

                        writeFromArray((byte[])bytes[1], output);
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

    private String loadSource(File in)
        throws IOException
    {
        StringBuilder str = new StringBuilder();
        InputStreamReader rdr = new InputStreamReader(new FileInputStream(in));
        char[] buf = new char[4096];
        int cr;

        if (codePrefix != null) {
            str.append(codePrefix);
        }
        try {
            do {
                cr = rdr.read(buf);
                if (cr > 0) {
                    str.append(buf, 0, cr);
                }
            } while (cr > 0);
        } finally {
            rdr.close();
        }
        if (codePostfix != null) {
            str.append(codePostfix);
        }
        return str.toString();
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
}
