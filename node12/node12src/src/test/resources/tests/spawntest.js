var assert = require('assert');
var child = require('child_process');

if (process.platform === 'win32') {
  process.exit(0);
}

assert(process.argv.length === 3);
var op = process.argv[2];

var gotClose = false;
var gotExit = false;
var gotError = false;

var echo;
try {
  echo = child.spawn('echo', ['Hello, World!']);
} catch (e) {
  if (op === 'fail') {
    process.exit(0);
  } else {
    throw e;
  }
}

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

echo.stdin.on('close', function() {
  console.log('stdin closed');
});
echo.stdout.on('close', function() {
  console.log('stdout closed');
});
echo.stderr.on('close', function() {
  console.log('stderr closed');
});
echo.stdout.on('data', function(chunk) {
  console.log('stdout: %s', chunk);
});
echo.stderr.on('data', function(chunk) {
  console.log('stderr: %s', chunk);
});