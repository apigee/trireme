package com.apigee.noderunner.samples.hadoop;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;

import java.io.IOException;

/**
 * This class may be run by Hadoop. It takes input and output directories as parameters,
 * and a script file name. It will run the script, invoking the "map" and "reduce"
 * methods on the script with string arguments. The script must be like a Node.js module
 * that has the "map" and "reduce" functions.
 */

public class HadoopMain
{
    private JobConf conf;

    public static void main(String[] args)
    {
        if (args.length != 3) {
            System.err.println("Usage: HadoopMain <input dir> <output dir> <script file>");
            return;
        }

        try {
            HadoopMain main = new HadoopMain();
            main.initializeHadoop();
            main.start(args[0], args[1], args[2]);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private HadoopMain()
    {
    }

    private void initializeHadoop()
    {
        conf = new JobConf(HadoopMain.class);
        conf.setJobName("noderunnersample");

        conf.setOutputKeyClass(Text.class);
        conf.setOutputValueClass(Text.class);

        conf.setInputFormat(TextInputFormat.class);
        conf.setOutputFormat(TextOutputFormat.class);

        conf.setMapperClass(HadoopMapper.class);
        conf.setReducerClass(HadoopReducer.class);
    }

    private void start(String inputDir, String outputDir, String scriptFileName)
        throws IOException
    {
        conf.set(HadoopBase.SCRIPT_FILE_KEY, scriptFileName);
        FileInputFormat.setInputPaths(conf, new Path(inputDir));
        FileOutputFormat.setOutputPath(conf, new Path(outputDir));
        JobClient.runJob(conf);
    }
}
