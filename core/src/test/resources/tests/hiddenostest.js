var assert = require('assert');
var os = require('os');

console.log('Obscured network list: %j', os.networkInterfaces());
console.log('Obscured CPU list: %j', os.cpus());

assert.equal(os.type(), 'Trireme');
assert.equal(os.release(), 'Trireme');
assert.equal(os.cpus().length, 1);
