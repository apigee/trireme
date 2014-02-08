#!/bin/sh

# This is a little utility script for running the whole NodeRunner process
# during development. It uses maven to create the classpath, then it
# starts the launcher.
# This way you can rebuild and relaunch without any additional steps.

CP=/tmp/cp.$$
rm -f ${CP}

mvn -q -DincludeScope=compile -Dmdep.outputFile=${CP} dependency:build-classpath

echo "`cat ${CP}`:${PWD}/target/classes"
