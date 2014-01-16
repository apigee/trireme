var http = require('http');
var assert = require('assert');

var socketsMatch = false;

var svr = http.createServer(function(req, resp) {
  if (process.env.ATTACHMENT) {
    console.log('Looking for attachment %s and found %s', process.env.ATTACHMENT, req.attachment);
    assert(req.attachment);
  }

  req.on('data', function(chunk) {
    console.error('Server should not have received any data on a GET');
    assert(false);
  });

  req.on('end', function() {
    assert(req.socket);
    assert(req.connection);
    assert.equal(req.socket, resp.socket);
    var msg = {
      localAddress: req.socket.localAddress,
      localPort: req.socket.localPort,
      remoteAddress: req.socket.remoteAddress,
      remotePort: req.socket.remotePort
    };

    resp.end(JSON.stringify(msg));
  });
});

svr.listen(33333, function() {
  http.get('http://localhost:33333/', function(resp) {
    var received = '';
    resp.setEncoding('utf8');
    if (resp.statusCode != 200) {
      process.exit(1);
    }

    resp.on('data', function(chunk) {
      received += chunk;
    });

    resp.on('end', function() {
      svr.close();

      var msg = JSON.parse(received);
      console.log('Received: %j', msg);

      assert.equal(resp.socket.localAddress, msg.remoteAddress);
      assert.equal(resp.socket.localPort, msg.remotePort);
      assert.equal(resp.socket.remoteAddress, msg.localAddress);
      assert.equal(resp.socket.remotePort, msg.localPort);
      socketsMatch = true;
    });
  });
});

process.on('exit', function() {
  assert(socketsMatch);
});

