#!/bin/sh

# This is a little utility script for running the whole NodeRunner process
# during development. It uses maven to create the classpath, then it
# starts the launcher.
# This way you can rebuild and relaunch without any additional steps.

CP=/tmp/cp.$$
rm -f ${CP}

mvn -q -DincludeScope=test -Dmdep.outputFile=${CP} dependency:build-classpath

HADOOP_CLASSPATH=`cat ${CP}`
export HADOOP_CLASSPATH
rm ${CP}

#echo "Classpath is set"

exec hadoop jar target/*.jar com.apigee.noderunner.samples.hadoop.HadoopMain $*

