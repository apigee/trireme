var assert = require('assert');
var http = require('http');
var util = require('util');

var socketsMatch = false;
var gotFinish = false;

var svr = http.createServer(function(req, resp) {
  try {
    if (process.env.ATTACHMENT) {
      console.log('Looking for attachment %s and found %s', process.env.ATTACHMENT, req.attachment);
      // "attachment" must match what we passed from Java code, but must not be enumerable.
      assert(req.attachment);
      assert.equal(false, Object.keys(req).some(function (v) {
        return (v === 'attachment');
      }));
      // make doubly-sure this doesn't throw
      util.inspect(req);
    }
  } catch (e) {
    console.log('Error: %j', e);
    resp.writeHead(500);
    resp.end(util.inspect(e));
    return;
  }

  req.on('data', function(chunk) {
    console.error('Server should not have received any data on a GET');
    assert(false);
  });

  req.on('end', function() {
    try {
      assert(req.socket);
      assert(req.connection);
      assert.equal(req.socket, resp.socket);
      var msg = {
        localAddress: req.socket.localAddress,
        localPort: req.socket.localPort,
        remoteAddress: req.socket.remoteAddress,
        remotePort: req.socket.remotePort
      };

      resp.on('finish', function() {
        gotFinish = true;
      });
      resp.end(JSON.stringify(msg));
    } catch (e) {
      resp.writeHead(500);
      resp.end(util.inspect(e));
    }
  });
});

svr.listen(0, function() {
  console.log('Server listening on %j', svr.address());
  var url = util.format('http://localhost:%d', svr.address().port);
  http.get(url, function(resp) {
    var received = '';
    resp.setEncoding('utf8');
    if (resp.statusCode != 200) {
      process.exit(1);
    }

    var localAddress = resp.socket.localAddress;
    var localPort = resp.socket.localPort;
    var remoteAddress = resp.socket.remoteAddress;
    var remotePort = resp.socket.remotePort;

    resp.on('data', function(chunk) {
      received += chunk;
    });

    resp.on('end', function() {
      svr.close();

      var msg = JSON.parse(received);
      console.log('Received: %j', msg);

      assert.equal(localPort, msg.remotePort);
      assert.equal(remotePort, msg.localPort);
      socketsMatch = true;
    });
  });
});

process.on('exit', function() {
  assert(socketsMatch);
  assert(gotFinish);
});

