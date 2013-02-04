// Copyright Joyent, Inc. and other Node contributors.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the
// following conditions:
//
// The above copyright notice and this permission notice shall be included
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
// NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
// USE OR OTHER DEALINGS IN THE SOFTWARE.


var common = require('../common');
var assert = require('assert');
var spawn = require('child_process').spawn;
var debug = require('_debugger');

var port = common.PORT + 1337;

var script = common.fixturesDir + '/breakpoints_utf8.js';

var child = spawn(process.execPath, ['debug', '--port=' + port, script]);

console.error('./node', 'debug', '--port=' + port, script);

var buffer = '';
child.stdout.setEncoding('utf-8');
child.stdout.on('data', function(data) {
  data = (buffer + data.toString()).split(/\n/g);
  buffer = data.pop();
  data.forEach(function(line) {
    child.emit('line', line);
  });
});
child.stderr.pipe(process.stdout);

var expected = [];

child.on('line', function(line) {
  line = line.replace(/^(debug> )+/, 'debug> ');
  console.error('line> ' + line);
  assert.ok(expected.length > 0, 'Got unexpected line: ' + line);

  var expectedLine = expected[0].lines.shift();
  assert.ok(line.match(expectedLine) !== null, line + ' != ' + expectedLine);

  if (expected[0].lines.length === 0) {
    var callback = expected[0].callback;
    expected.shift();
    callback && callback();
  }
});

function addTest(input, output) {
  function next() {
    if (expected.length > 0) {
      child.stdin.write(expected[0].input + '\n');

      if (!expected[0].lines) {
        setTimeout(function() {
          var callback = expected[0].callback;
          expected.shift();

          callback && callback();
        }, 50);
      }
    } else {
      finish();
    }
  };
  expected.push({input: input, lines: output, callback: next});
}

// Initial lines
addTest(null, [
  /listening on port \d+/,
  /connecting... ok/,
  /break in .*:1/,
  /1/, /2/, /3/
]);

// Next
addTest('n', [
  /break in .*:11/,
  /9/, /10/, /11/, /12/, /13/
]);

// Watch
addTest('watch("\'x\'")');

// Continue
addTest('c', [
  /break in .*:5/,
  /Watchers/,
  /0:\s+'x' = "x"/,
  /()/,
  /3/, /4/, /5/, /6/, /7/
]);

// Show watchers
addTest('watchers', [
  /0:\s+'x' = "x"/
]);

// Unwatch
addTest('unwatch("\'x\'")');

// Step out
addTest('o', [
  /break in .*:12/,
  /10/, /11/, /12/, /13/, /14/
]);

// Continue
addTest('c', [
  /break in .*:5/,
  /3/, /4/, /5/, /6/, /7/
]);

// Set breakpoint by function name
addTest('sb("setInterval()", "!(setInterval.flag++)")', [
  /1/, /2/, /3/, /4/, /5/, /6/, /7/, /8/, /9/, /10/
]);

// Continue
addTest('c', [
  /break in node.js:\d+/,
  /\d/, /\d/, /\d/, /\d/, /\d/
]);

function finish() {
  process.exit(0);
}

function quit() {
  if (quit.called) return;
  quit.called = true;
  child.stdin.write('quit');
}

setTimeout(function() {
  console.error('dying badly');
  var err = 'Timeout';
  if (expected.length > 0 && expected[0].lines) {
    err = err + '. Expected: ' + expected[0].lines.shift();
  }
  quit();
  child.kill('SIGINT');
  child.kill('SIGTERM');

  // give the sigkill time to work.
  setTimeout(function() {
    throw new Error(err);
  }, 100);

}, 5000);

process.once('uncaughtException', function(e) {
  quit();
  console.error(e.toString());
  process.exit(1);
});

process.on('exit', function(code) {
  quit();
  if (code === 0) {
    assert.equal(expected.length, 0);
  }
});
