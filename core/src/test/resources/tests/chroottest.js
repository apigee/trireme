var fs = require('fs');
var assert = require('assert');

GOOD_FILE = './tests/chroottest.js';
BAD_FILE = './tests/chroottest.jsXXX';
INVALID_FILE = '../../chroottest.js';

console.log('Running from ' + process.cwd());

// These paths should be translated to add "./target/test-classes" before:
assert.doesNotThrow(
  function() {
    var myself = fs.openSync(GOOD_FILE, 'r');
    fs.close(myself);
  }, 'Error opening ' + GOOD_FILE);

assert.doesNotThrow(
  function() {
    fs.statSync(GOOD_FILE);
  }, 'Error statting ' + GOOD_FILE);

// These paths should never work
assert.throws(
  function() {
    fs.openSync(BAD_FILE, 'r');
  },
  /ENOENT/, 'Error opening ' + BAD_FILE);

assert.throws(
  function() {
    fs.statSync(BAD_FILE);
  },
  /ENOENT/, 'Error statting ' + BAD_FILE);

// These paths should never work upon translation
assert.throws(
  function() {
    fs.openSync(INVALID_FILE, 'r');
  },
  /ENOENT/, 'Error opening ' + INVALID_FILE);

assert.throws(
  function() {
    fs.statSync(INVALID_FILE);
  },
  /ENOENT/, 'Error statting ' + INVALID_FILE);

process.exit(0);
