var assert = require('assert');
var natives = process.binding('natives');

assert(natives.fs);
assert(natives.fs.length > 0);