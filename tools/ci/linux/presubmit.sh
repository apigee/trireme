#!/bin/sh

BUILDROOT=${BUILDROOT:-github/trireme}

(cd $BUILDROOT; mvn clean install)
testStatus=$?

exit ${testStatus}
