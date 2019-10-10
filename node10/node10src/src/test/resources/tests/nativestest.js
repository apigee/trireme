console.log('Natives test...');
var assert = require('assert');
var natives = process.binding('natives');
console.log('... requires done');

assert(natives);
assert(natives.fs);
assert(natives.fs.length > 0);