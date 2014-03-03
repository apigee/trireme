var assert = require('assert');

assert(process.argv[2]);
assert(process.argv[3]);

var dest = process.argv[2];
var msg = process.argv[3];

if (dest === 'stdout') {
  console.log(msg);
} else if (dest === 'stderr') {
  console.error(msg);
} else {
  assert(false);
}

