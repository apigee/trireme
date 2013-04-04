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

/*
 * Modified this version for NodeRunner SSL: The "authorized"
 * option is not available, and it uses a Java keystore and 
 * trust store.
 */

if (!process.versions.openssl) {
  console.error('Skipping because node compiled without OpenSSL.');
  process.exit(0);
}

var common = require('../common');
var assert = require('assert');
var tls = require('tls');
var fs = require('fs');
var path = require('path');

var options = {
  keystore: path.join(common.fixturesDir, 'test.jks'),
  passphrase: 'secure'
};

var connectCount = 0;

var server = tls.createServer(options, function(socket) {
  ++connectCount;
  socket.on('data', function(data) {
    common.debug(data.toString());
    assert.equal(data, 'ok');
  });
  socket.end();
});
server.on('clientError', function(err, conn) {
  console.error('Client error found on server: ' + err);
  conn.destroy();
});
server.listen(common.PORT, function() {
  unauthorized();
});

function unauthorized() {
  var socket = tls.connect({
    port: common.PORT,
    rejectUnauthorized: false
  }, function() {
    console.log('Succeeded correctly with rejectUnauthorized = true');
    socket.end();
    rejectUnauthorized();
  });
  socket.on('error', function(err) {
    assert(false);
  });
  socket.write('ok');
  socket.end();
}

function rejectUnauthorized() {
  var socket = tls.connect(common.PORT, function() {
    assert(false);
  });
  socket.on('error', function(err) {
    console.log('Failed as expected with defaults');
    common.debug(err);
    authorized();
  });
  socket.write('ng');
  socket.end();
}

function authorized() {
  var socket = tls.connect(common.PORT, {
    truststore: path.join(common.fixturesDir, 'test.jks'),
    passphrase: 'secure'
  }, function() {
    console.log('Succeeded as expected using truststore');
    socket.end();
    server.close();
  });
  socket.on('error', function(err) {
    assert(false);
  });
  socket.write('ok');
}

process.on('exit', function() {
  // The failed handshake doesn't count as a connect in NR
  assert.equal(connectCount, 2);
});
