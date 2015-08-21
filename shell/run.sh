#!/bin/sh

# This is a little utility script for running the whole NodeRunner process
# during development. It uses maven to create the classpath, then it
# starts the launcher.
# This way you can rebuild and relaunch without any additional steps.

CP=/tmp/cp.$$
rm -f ${CP}

mvn -q -DincludeScope=test -Dmdep.outputFile=${CP} dependency:build-classpath

CLASSPATH=./target/classes:./target/test-classes:`cat ${CP}`
export CLASSPATH
rm ${CP}

JARGS="-Xmx1g \
  -Dorg.slf4j.simpleLogger.showThreadName=false \
  -Dorg.slf4j.simpleLogger.showShortLogName=true \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=${LOGLEVEL:-info}"
#JARGS="-Xmx1g -DHttpAdapter=netty"
#JARGS="-Xmx1g -DSealRoot=false -DOptLevel=1"
#JARGS="-Xdebug -Xrunjdwp:server=y,suspend=n,transport=dt_socket,address=localhost:14000"
#JARGS="-Xdebug -Xrunjdwp:server=y,suspend=n,transport=dt_socket,address=localhost:14000 -DHttpAdapter=netty"

#echo "Classpath is set"
exec java ${JARGS} io.apigee.trireme.shell.Main $*
