#!/bin/sh

CP=/tmp/cp.$$
rm -f ${CP}

mvn -DincludeScope=test -Dmdep.outputFile=${CP} dependency:build-classpath

CLASSPATH=./target/classes:./target/test-classes:`cat ${CP}`
export CLASSPATH
rm ${CP}

exec java com.apigee.noderunner.core.NodeRunner $*
