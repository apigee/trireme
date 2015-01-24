/*
 * Test the environment passed to the script. Starting with argument 1, the first argument is a name
 * and second is the expected value...
 */

var assert = require('assert');

var i = 2;
while (i < (process.argv.length - 1)) {
  var key = process.argv[i++];
  var val = process.argv[i++];

  console.log('Comparing %s=%s to %s=%s', key, val, key, process.env[key]);
  assert.equal(val, process.env[key]);
}

/*
 * Also test case-insensitivity of variables.
 */

// Variables passed by the caller
assert.equal(process.env.UPPERCASE, 'BIGANDSTRONG');
assert.equal(process.env.Lowercase, 'useful');

// Case-insensitivity
assert.equal(process.env.LOWERCASE, 'useful');

assert(Object.keys(process.env).some(function(v) {
  return (v === 'UPPERCASE');
}));

// Lower-case ones should appear in their original case only when enumerated
assert(Object.keys(process.env).some(function(v) {
  return (v === 'Lowercase');
}));
assert.equal(false, Object.keys(process.env).some(function(v) {
  return (v === 'LOWERCASE');
}));