#!/bin/sh

CP=/tmp/testclasspath.$$
rm -f ${CP}
mvn -DincludeScope=test -Dmdep.outputFile=${CP} dependency:build-classpath

CLASSPATH=${PWD}/target/classes:${PWD}/target/test-classes:`cat ${CP}`
export CLASSPATH
rm ${CP}

ARGS="-p verbose"

cd src/test/python

if [ $# -eq 0 ]
then
  python test.py ${ARGS} simple
else
  python test.py ${ARGS} $*
fi
