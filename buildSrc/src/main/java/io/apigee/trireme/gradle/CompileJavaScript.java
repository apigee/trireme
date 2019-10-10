package io.apigee.trireme.gradle;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileType;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.optimizer.ClassCompiler;

public abstract class CompileJavaScript extends DefaultTask {
  public static final boolean DEFAULT_DEBUG = true;
  public static final int DEFAULT_OPTIMIZATION = 9;
  public static final boolean DEFAULT_GENERATING_SOURCE = true;
  public static final boolean DEFAULT_GENERATING_OBSERVER_COUNT = false;
  public static final int DEFAULT_LANGUAGE_VERSION = Context.VERSION_1_8;

  private int languageVersion = DEFAULT_LANGUAGE_VERSION;
  private String codePrefix;
  private String codeSuffix;
  private boolean generatingObserverCount = DEFAULT_GENERATING_OBSERVER_COUNT;
  private String macroFile;

  /** This is the directory where we scan for source from */
  @Incremental
  @PathSensitive(PathSensitivity.NAME_ONLY)
  @InputDirectory
  abstract DirectoryProperty getInputDir();

  /** This is the directory where output goes. */
  @OutputDirectory
  abstract DirectoryProperty getOutputDir();

  /** If set, this is the directory that's used as the base location for 
   *  using relative path names to name output files and classes. */
  @PathSensitive(PathSensitivity.NAME_ONLY)
  @InputDirectory
  @Optional
  abstract DirectoryProperty getInputBaseDir();

  /** If set, source files are copied into this directory. */
  @PathSensitive(PathSensitivity.NAME_ONLY)
  @InputDirectory
  @Optional
  abstract DirectoryProperty getSourceOutputDir();

  @Input
  @Optional
  Integer getLanguageVersion() {
    return languageVersion;
  }

  void setLanguageVersion(Integer v) {
    languageVersion = v;
  }

  @Input
  @Optional
  String getCodePrefix() {
    return codePrefix;
  }

  void setCodePrefix(String p) {
    codePrefix = p;
  }

  @Input
  @Optional
  String getCodeSuffix() {
    return codeSuffix;
  }

  void setCodeSuffix(String s) {
    codeSuffix = s;
  }

  @Input
  @Optional
  Boolean getGeneratingObserverCount() {
    return generatingObserverCount;
  }

  void setGeneratingObserverCount(Boolean g) {
    this.generatingObserverCount = g;
  }

  @Input
  @Optional
  public String getMacroFile() {
    return macroFile;
  }

  public void setMacroFile(String f) {
    macroFile = f;
  }

  @TaskAction
  void execute(InputChanges changes) throws IOException {
    final ClassCompiler comp = new ClassCompiler(makeCompilerEnvirons());

    Path srcDir;
    if (getInputBaseDir().isPresent()) {
      srcDir = getInputBaseDir().getAsFile().get().toPath();
    } else {
      srcDir = getInputDir().getAsFile().get().toPath();
    }
    Path srcOutDir = null;
    if (getSourceOutputDir().isPresent()) {
      srcOutDir = getSourceOutputDir().getAsFile().get().toPath();
    }
    final Path destDir = getOutputDir().getAsFile().get().toPath();

    for (FileChange fc : changes.getFileChanges(getInputDir())) {
      if (fc.getFileType() == FileType.FILE) {
        // Ignore directory changes and "missing" files
        final Path inPath = fc.getFile().toPath();
        String fileName = inPath.getFileName().toString();
        if (!fileName.endsWith(".js")) {
          System.out.println(inPath + " is not a JavaScript file");
          continue;
        }
        final String classFileName = fileName.substring(0, fileName.length() - 3) + ".class";
        final Path relPath = Paths.get(srcDir.relativize(inPath.getParent()).toString(), classFileName);
        final Path outPath = destDir.resolve(relPath);
        Path srcOutPath = null;
        if (srcOutDir != null) {
          srcOutPath = srcOutDir.resolve(srcDir.relativize(inPath));
        }        

        final String relPathStr = relPath.toString();
        final String className = relPathStr.substring(0, relPathStr.length() - 6).replaceAll("[/\\\\]", ".");

        if (fc.getChangeType() == ChangeType.REMOVED) {
          System.out.println("Removing " + outPath);
          Files.deleteIfExists(outPath);
        } else {
          compile(comp, className, inPath, outPath, srcOutPath);
        }
      }
    }
  }

  private void compile(ClassCompiler comp, String className, Path in, Path out, Path srcOut) throws IOException {
    System.out.println("Compiling " + in.getFileName() + " to " + className);

    final String source = loadSource(in);

    Object[] byteCode;
    try {
      byteCode = comp.compileToClassFiles(addPrefixes(source), in.getFileName().toString(), 1, className);
    } catch (RhinoException re) {
      throw new IOException(re);
    }
    if (byteCode.length > 2) {
      throw new IOException("Compiler produced more than one class, which was not expected");
    }
    // Who remembers why there are two byte codes...
    writeFromArray((byte[])byteCode[1], out);
    if (srcOut != null) {
      writeFromString(source, srcOut);
    }
  }

  private String loadSource(Path in) throws IOException {
    MacroProcessor macro = null;
    if (macroFile != null) {
      macro = new MacroProcessor(macroFile);
    }

    final StringBuilder str = new StringBuilder();

    try (BufferedReader rdr = new BufferedReader(new FileReader(in.toFile()))) {
      String line;
      do {
        line = rdr.readLine();
        if (line != null) {
          if (macro != null) {
            line = macro.processLine(line);
          }
          str.append(line).append('\n');
        }
      } while (line != null);
    }
    return str.toString();
  }

  private String addPrefixes(String s)
  {
      StringBuilder p = new StringBuilder(s.length());
      if (codePrefix != null) {
          p.append(codePrefix);
      }
      p.append(s);
      if (codeSuffix != null) {
          p.append(codeSuffix);
      }
      return p.toString();
  }

  private void writeFromArray(byte[] bytes, Path out) throws IOException {
    if (Files.exists(out)) {
      Files.delete(out);
    }
    Files.createDirectories(out.getParent());
    try (FileOutputStream of = new FileOutputStream(out.toFile())) {
      of.write(bytes);
    }
  }

  private void writeFromString(String src, Path out) throws IOException {
    if (Files.exists(out)) {
      Files.delete(out);
    }
    Files.createDirectories(out.getParent());
    try (FileWriter w = new FileWriter(out.toFile())) {
      w.append(src);
    }
  }

  private CompilerEnvirons makeCompilerEnvirons() {
    // Since this is only used in our own project, we hard-code these. A "real"
    // plugin would
    // have them all configurable
    CompilerEnvirons env = new CompilerEnvirons();
    env.setLanguageVersion(languageVersion);
    env.setGenerateDebugInfo(DEFAULT_DEBUG);
    env.setOptimizationLevel(DEFAULT_OPTIMIZATION);
    env.setGeneratingSource(DEFAULT_GENERATING_SOURCE);
    env.setRecordingComments(false);
    env.setRecoverFromErrors(false);
    env.setGenerateObserverCount(generatingObserverCount);
    return env;
  }
}
