package io.apigee.trireme.shell.test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

public class ShellLauncher
{
    public static final String HEAP_SIZE = "-Xmx1g";

    private static String javaCommand;

    static {
        javaCommand = findJava("JAVA_HOME");
        if (javaCommand == null) {
            javaCommand = "java";
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

    public String execute(String[] callerArgs)
        throws IOException, InterruptedException
    {
        ArrayList<String> args = new ArrayList<String>();
        args.add(javaCommand);
        args.add(HEAP_SIZE);
        args.add("-DLOGLEVEL=" + System.getProperty("LOGLEVEL", "INFO"));
        /* TODO
        if (coverage && (System.getProperty("CoverageArg") != null)) {
            args.add(System.getProperty("CoverageArg"));
        }
        */
        args.add("io.apigee.trireme.shell.Main");
        args.addAll(Arrays.asList(callerArgs));

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Map<String, String> envVars = pb.environment();
        envVars.put("CLASSPATH", System.getProperty("surefire.test.class.path"));
        Process proc = pb.start();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] output = new byte[8192];
        int r;
        do {
            r = proc.getInputStream().read(output);
            if (r > 0) {
                bos.write(output, 0, r);
            }
        } while (r > 0);

        proc.waitFor();

        return bos.toString("utf8");
    }
}
