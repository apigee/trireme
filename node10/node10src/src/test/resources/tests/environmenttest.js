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
