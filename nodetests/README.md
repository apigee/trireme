# Noderunner Tests

This directory contains a copy of the test suite from node.js, and we can use it to run those tests in
noderunner.

The scripts in the directory "src/test/resources/test/simple" will all be run my Maven as part of the "test"
phase. If any returns a non-zero exit status, it is considered to have failed.

To run a single test, set the "TestFile" property to the base name of the file (without the path
component). For instance, the follow command will run the test script "test-fs-long-path.js" and then exit:

mvn -DTestFile=test-fs-long-path.js test

To enable Noderunner debugging (that is, debugging of Noderunner's Java code), set the LOGLEVEL
property to one of the supported SLF4J log levels such as INFO or DEBUG. The default is INFO.

Right now, the tests to be run are in src/test/resources/test/simple. Tests in other directories (including
the "broken" directory) are not currently run.

