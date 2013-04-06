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
var tls = require('tls');
var net = require('net');
var fs = require('fs');
var path = require('path');

var serverConnected = false;
var clientConnected = false;

var options = {
  keystore: path.join(common.fixturesDir, 'test.jks'),
  passphrase: 'secure'
};

console.log('Connecting with ' + JSON.stringify(options));
var server = tls.createServer(options, function(socket) {
  console.log('Server connected');
  serverConnected = true;
  socket.end('Hello');
  console.log('end sent');
});
server.listen(common.PORT, function() {
  var socket = net.connect({
    port: common.PORT,
    rejectUnauthorized: false
  }, function() {
    var client = tls.connect({
      rejectUnauthorized: false,
      socket: socket
    }, function() {
      console.log('Client connected');
      clientConnected = true;
      var data = '';
      client.on('data', function(chunk) {
        console.log('Client got data');
        data += chunk.toString();
      });
      client.on('end', function() {
        console.log('Client got end');
        assert.equal(data, 'Hello');
        server.close();
      });
    });
  });
});

process.on('exit', function() {
  assert(serverConnected);
  assert(clientConnected);
});
