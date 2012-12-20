# Noderunner Tests

This directory contains a copy of the test suite from node.js, and we can use it to run those tests in
noderunner.

The script "runtests.sh" runs the Node Python test suite using noderunner. It'd be cool to have this integrated
with Maven but it's not set up now. The test suite relies on Python so that'd take a little work.

The test suites are in "src/test/python." Currently, parts of the "simple" test suite are there
but there is a lot of work to do.

Tests that are known not to work are in the "broken" subdirectory. Tests that are supposed to work are in
the "simple" directory. As we get each test case working, we move it from "broken" to "simple" and then
it can be part of the regular tests.
