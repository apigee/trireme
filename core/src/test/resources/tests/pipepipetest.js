var pw = process.binding('pipe_wrap');
var net = require('net');
var assert = require('assert');

var pipe1 = new pw.Pipe();
var pipe2 = new pw.Pipe();

pipe1._setupPipe(pipe2);

var sock1 = new net.Socket({ handle: pipe1 });
var sock2 = new net.Socket({ handle: pipe2 });

var data = '';

sock1.write('Hello');

sock2.setEncoding('utf8');
sock2.on('data', function(chunk) {
  data += chunk;
});

console.log('Writing to sock1');
sock1.write('Testing');
sock1.write('123');

var successes = 0;
// Possibly we could make this more robust, but we need to wait for everything to drain
setTimeout(function() {
  assert('HelloTesting123', data);
  successes++;
}, 500);

pipe2._setupPipe(pipe1);

var data2 = '';
sock1.setEncoding('utf8');
sock1.on('data', function(chunk) {
  data2 += chunk;
});

sock2.write('123Testing');

setTimeout(function() {
  assert.equal('123Testing', data2);
  successes++;
}, 501);

process.on('exit', function() {
  assert.equal(successes, 2);
});
