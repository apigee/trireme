# Noderunner Tests

This directory contains a copy of the test suite from node.js, and we can use it to run those tests in
noderunner.

The scripts in the directory "src/test/resources/test/simple" will all be run my Maven as part of the "test"
phase. If any returns a non-zero exit status, it is considered to have failed.

Tests that are known not to work are in the "broken" subdirectory. Tests that are supposed to work are in
the "simple" directory. As we get each test case working, we move it from "broken" to "simple" and then
it can be part of the regular tests.
