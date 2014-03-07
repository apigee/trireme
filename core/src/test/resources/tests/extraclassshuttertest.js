var assert = require('assert');
var actualFilePath = new java.io.File(__filename).getCanonicalPath();
var expectedFilePath = process.argv[2];

assert (actualFilePath.equals(expectedFilePath));

// We have to use "==" here because Rhino thinks actualFilePath is an object instead of a string
assert (actualFilePath == expectedFilePath);
