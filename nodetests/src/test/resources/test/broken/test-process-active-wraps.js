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
var net = require('net');

function expect(activeHandles, activeRequests) {
  assert.equal(process._getActiveHandles().length, activeHandles);
  assert.equal(process._getActiveRequests().length, activeRequests);
}

(function() {
  expect(0, 0);
  var server = net.createServer().listen(common.PORT);
  expect(1, 0);
  server.close();
  expect(1, 0); // server handle doesn't shut down until next tick
})();

(function() {
  expect(1, 0);
  var conn = net.createConnection(common.PORT);
  conn.on('error', function() { /* ignore */ });
  expect(2, 1);
  conn.destroy();
  expect(2, 1); // client handle doesn't shut down until next tick
})();

// Force the nextTicks to be deferred to a later time.
process.maxTickDepth = 1;

process.nextTick(function() {
  process.nextTick(function() {
    process.nextTick(function() {
      process.nextTick(function() {
        // the handles should be gone but the connect req could still be alive
        assert.equal(process._getActiveHandles().length, 0);
      });
    });
  });
});
