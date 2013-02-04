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

var net = require('net');

var N = 50;
var c = 0;
var client_recv_count = 0;
var disconnect_count = 0;

var server = net.createServer(function(socket) {
  socket.on('connect', function() {
    console.log('Server connected');
    socket.write('hello\r\n');
  });

  socket.on('end', function() {
    socket.end();
  });

  socket.on('close', function(had_error) {
    //console.log('server had_error: ' + JSON.stringify(had_error));
    assert.equal(false, had_error);
  });
});

server.listen(common.PORT, function() {
  console.log('listening');
  var client = net.createConnection(common.PORT);

  // TODO GREG Invalid isn't this an invalid charset according to other tests?
  //client.setEncoding('UTF8');
  client.setEncoding('utf8');

  client.on('connect', function() {
    console.log('client connected.');
  });

  client.on('data', function(chunk) {
    client_recv_count += 1;
    console.log('client_recv_count ' + client_recv_count);
    assert.equal('hello\r\n', chunk);
    client.end();
  });

  client.on('close', function(had_error) {
    console.log('disconnect');
    assert.equal(false, had_error);
    if (disconnect_count++ < N)
      client.connect(common.PORT); // reconnect
    else
      server.close();
  });
});

process.on('exit', function() {
  assert.equal(N + 1, disconnect_count);
  assert.equal(N + 1, client_recv_count);
});

