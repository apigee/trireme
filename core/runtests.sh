#!/bin/sh

CP=/tmp/testclasspath.$$
rm -f ${CP}
mvn -DincludeScope=test -Dmdep.outputFile=${CP} dependency:build-classpath

CLASSPATH=${PWD}/target/classes:${PWD}/target/test-classes:`cat ${CP}`
export CLASSPATH
rm ${CP}

cd src/test/python
python test.py
