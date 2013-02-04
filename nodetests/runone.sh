#!/bin/sh

CP=/tmp/cp.$$
rm -f ${CP}

mvn -DincludeScope=test -Dmdep.outputFile=${CP} dependency:build-classpath

CLASSPATH=./target/classes:./target/test-classes:`cat ${CP}`
export CLASSPATH
rm ${CP}

#ARGS="-Xdebug -Xrunjdwp:server=y,suspend=y,transport=dt_socket,address=localhost:14000"

rm -rf target/test-classes/test/tmp
time java ${ARGS} com.apigee.noderunner.test.TestRunner target/test-classes/test/$1
