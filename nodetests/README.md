# Noderunner Tests

This directory contains a copy of the test suite from node.js, and we can use it to run those tests in
noderunner.

The tests are disabled by default because they take so long to run. In order to really run them run maven
with the property "skipTests" to false, as in "mvn -DskipTests=false test".

The scripts in the directory "src/test/resources/test/simple" will all be run my Maven as part of the "test"
phase. If any returns a non-zero exit status, it is considered to have failed.

To run a single test, set the "TestFile" property to the base name of the file (without the path
component). For instance, the follow command will run the test script "test-fs-long-path.js" and then exit:

mvn -DskipTests=false -DTestFile=test-fs-long-path.js test

To run a subset of the tests, do the same thing but include only part of the file name -- "TestFile" will match
any file name that contains the complete string. So to run all the http tests:

mvn -DskipTests=false -DTestFile=test-http test

To enable Noderunner debugging (that is, debugging of Noderunner's Java code), set the LOGLEVEL
property to one of the supported SLF4J log levels such as INFO or DEBUG. The default is INFO.

Right now, the tests to be run are in src/test/resources/test/simple. Tests in other directories (including
the "broken" directory) are not currently run.

