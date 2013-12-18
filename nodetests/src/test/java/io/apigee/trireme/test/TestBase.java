package io.apigee.trireme.test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;

public abstract class TestBase
{
    public static final int DEFAULT_TIMEOUT = 60;

    protected final File fileName;
    protected final String adapter;
    protected final String javaVersion;

    private static String java6Command;
    private static String java7Command;
    private static String javaCommand;
    protected static String[] javaVersions;

    public static final String DEFAULT_ADAPTER = "default";
    public static final String RESULT_FILE = "target/benchmark.out";

    static {
        java6Command = findJava("JAVA_HOME_6");
        java7Command = findJava("JAVA_HOME_7");
        javaCommand = findJava("JAVA_HOME");
        if (javaCommand == null) {
            javaCommand = "java";
        }

        System.out.println("Java 6:  " + java6Command);
        System.out.println("Java 7:  " + java7Command);
        System.out.println("Default: " + javaCommand);

        if ((java6Command != null) && (java7Command != null)) {
            javaVersions = new String[] { "6", "7" };
        } else if (java7Command != null) {
            javaVersions = new String[] { "7" };
        } else if (java6Command != null) {
            javaVersions = new String[] { "6" };
        } else {
            javaVersions = new String[] { "default" };
        }
    }

    private static String findJava(String javaHome)
    {
        String home = System.getenv(javaHome);
        if (home != null) {
            File javaFile = new File(home + "/bin/java");
            if (javaFile.exists() && javaFile.canExecute()) {
                return javaFile.getPath();
            }
        }
        return null;
    }

    protected TestBase(File fileName, String adapter, String javaVersion)
    {
        this.fileName = fileName;
        this.adapter = adapter;
        this.javaVersion = javaVersion;
    }

    protected int launchTest(int timeout, OutputStream o, boolean coverage)
        throws IOException, InterruptedException
    {
        String command;
        if ("6".equals(javaVersion)) {
            command = java6Command;
        } else if ("7".equals(javaVersion)) {
            command = java7Command;
        } else {
            command = javaCommand;
        }

        OutputStream stdout = (o == null ? System.out : o);

        ArrayList<String> args = new ArrayList<String>();
        args.add(command);
        args.add("-DLOGLEVEL=" + System.getProperty("LOGLEVEL", "INFO"));
        if (coverage && (System.getProperty("CoverageArg") != null)) {
            args.add(System.getProperty("CoverageArg"));
        }
        args.add("io.apigee.trireme.test.TestRunner");
        args.add(fileName.getName());
        args.add(adapter);
        args.add(String.valueOf(timeout));

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(fileName.getParentFile());
        pb.redirectErrorStream(true);
        Map<String, String> envVars = pb.environment();
        envVars.put("CLASSPATH", System.getProperty("surefire.test.class.path"));
        Process proc = pb.start();

        byte[] output = new byte[8192];
        int r;
        do {
            r = proc.getInputStream().read(output);
            if (r > 0) {
                stdout.write(output, 0, r);
            }
        } while (r > 0);

        return proc.waitFor();
    }
}
