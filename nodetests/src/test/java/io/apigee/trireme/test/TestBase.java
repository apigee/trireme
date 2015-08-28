package io.apigee.trireme.test;

import io.apigee.trireme.core.NodeEnvironment;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class TestBase
{
    public static final int DEFAULT_TIMEOUT = 30;
    public static final String HEAP_SIZE = "-Xmx1g";

    protected final File fileName;
    protected final String adapter;
    protected final String javaVersion;
    protected final String nodeVersion;
    protected final NodeEnvironment nodeEnvironment;

    private static String java6Command;
    private static String java7Command;
    private static String javaCommand;
    protected static String[] javaVersions;
    protected static Pattern[] forkPatterns;

    public static final String DEFAULT_ADAPTER = "default";

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

        /*
         * A few of the tests don't work unless Trireme is run in a separate, forked, JVM.
         * Given the nature of those tests, that is not necessarily a bug ;-).
         * These patterns override whatever Java version is set so that they fork.
         */
        forkPatterns = new Pattern[] {
            Pattern.compile("^test-child.*"),
            Pattern.compile("^test-chdir.*")
            //Pattern.compile("^test-regress.*")
        };
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

    protected TestBase(File fileName, String adapter, String javaVersion, String nodeVersion)
    {
        this.fileName = fileName;
        this.adapter = adapter;
        this.javaVersion = javaVersion;
        this.nodeVersion = nodeVersion;
        this.nodeEnvironment = new NodeEnvironment();
    }

    protected int launchTest(int timeout, OutputStream o, boolean coverage)
        throws IOException, InterruptedException
    {
        String command;
        boolean fork = false;
        if ("6".equals(javaVersion)) {
            command = java6Command;
            fork = true;
        } else if ("7".equals(javaVersion)) {
            command = java7Command;
            fork = true;
        } else {
            command = javaCommand;
        }

        for (Pattern fp : forkPatterns) {
            if (fp.matcher(fileName.getName()).matches()) {
                fork = true;
            }
        }

        if (fork) {
            return launchForkedTest(command, timeout, o, coverage);
        }
        return launchLocalTest(timeout, o);
    }

    private int launchForkedTest(String command, int timeout, OutputStream o, boolean coverage)
        throws IOException, InterruptedException
    {
        OutputStream stdout = (o == null ? System.out : o);

        ArrayList<String> args = new ArrayList<String>();
        args.add(command);
        args.add(HEAP_SIZE);
        args.add("-DLOGLEVEL=" + System.getProperty("LOGLEVEL", "INFO"));
        if (coverage && (System.getProperty("CoverageArg") != null)) {
            args.add(System.getProperty("CoverageArg"));
        }
        args.add("io.apigee.trireme.test.TestRunner");
        args.add(fileName.getName());
        args.add(adapter);
        args.add(String.valueOf(timeout));
        args.add(nodeVersion);

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

    private int launchLocalTest(int timeout, OutputStream o)
        throws IOException
    {
        return TestRunner.runTest(nodeEnvironment, o, fileName, fileName.getParentFile(), nodeVersion, timeout);
    }
}
