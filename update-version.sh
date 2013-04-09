#!/bin/sh

if [ $# -ne 1 ]
then
  echo "Usage: update-versions <version>"
  exit 1
fi

mvn release:update-versions -DautoVersionSubmodules=true \
  -DdevelopmentVersion=${1}
