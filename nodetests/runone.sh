#!/bin/sh

CP=/tmp/cp.$$
rm -f ${CP}

mvn -DincludeScope=test -Dmdep.outputFile=${CP} dependency:build-classpath

CLASSPATH=$PWD/target/classes:$PWD/target/test-classes:`cat ${CP}`
export CLASSPATH
rm ${CP}

#td=./src/test/resources/test/tmp
#rm -rf ${td}
#mkdir ${td}

rm -f ../node10/node10tests/tmp/*

ARGS=-Xmx1g

if [ $1 == "-d" ]
then
  ARGS="-Xdebug -Xrunjdwp:server=y,suspend=y,transport=dt_socket,address=localhost:14000"
  cd `dirname $2`; java ${ARGS} io.apigee.trireme.test.TestRunner `basename $2` $3 $4 $5 $6 $7

else 
  cd `dirname $1`; java ${ARGS} io.apigee.trireme.test.TestRunner `basename $1` $2 $3 $4 $5 $6
fi
