#!/bin/sh

CP=/tmp/cp.$$
rm -f ${CP}

mvn -DincludeScope=test -Dmdep.outputFile=${CP} dependency:build-classpath

CLASSPATH=./target/classes:./target/test-classes:`cat ${CP}`
export CLASSPATH
rm ${CP}

if [ $1 == "-d" ]
then
  ARGS="-Xdebug -Xrunjdwp:server=y,suspend=y,transport=dt_socket,address=localhost:14000"
  java ${ARGS} com.apigee.noderunner.test.TestRunner $2 $3

else 
  java ${ARGS} com.apigee.noderunner.test.TestRunner $1 $2
fi
