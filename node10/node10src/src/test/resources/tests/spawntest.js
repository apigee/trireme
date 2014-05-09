var assert = require('assert');
var child = require('child_process');

assert(process.argv.length === 3);
var op = process.argv[2];

var gotClose = false;
var gotExit = false;
var gotError = false;

var echo = child.spawn('echo', ['Hello, World!']);

echo.on('exit', function(code, signal) {
  console.log('Got exit %d', code);
  assert(code !== null);
  gotExit = true;

});

echo.on('close', function(code, signal) {
  console.log('Got close %s', code);
  gotClose = true;
});

echo.on('error', function(err) {
  console.log('Got error: %j', err);
  gotError = true;
});

process.on('exit', function() {
  if (op === 'fail') {
    assert(gotError);
  } else {
    assert(gotExit);
    assert(gotClose);
  }
});
