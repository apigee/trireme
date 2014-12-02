var assert = require('assert');
var http = require('http');

var serverRecvCount = 0;
var serverRecvEnd = false;

// Create an HTTP server
var srv = http.createServer(function (req, res) {
  res.writeHead(200, {'Content-Type': 'text/plain'});
  res.end('okay');
});

srv.on('upgrade', function(req, socket, head) {
  socket.write('HTTP/1.1 101 Web Socket Protocol Handshake\r\n' +
               'Upgrade: WebSocket\r\n' +
               'Connection: Upgrade\r\n' +
               '\r\n');

  var allData = head.toString('utf8');
  socket.setEncoding('utf8');
  socket.on('data', function(chunk) {
    allData += chunk;
    console.log('Server: have %s', allData);

    while (/^PACK/.test(allData)) {
      // We got a message
      serverRecvCount++;
      socket.write('ACK');
      allData = allData.substring(4);
    }
  });
  socket.on('end', function() {
    console.log('Server got end');
    serverRecvEnd = true;
    socket.end();
  });
});

// now that server is running
srv.listen(33334, function() {

  // make a request
  var options = {
    port: 33334,
    hostname: 'localhost',
    headers: {
      'Connection': 'Upgrade',
      'Upgrade': 'websocket'
    }
  };

  var req = http.request(options);
  req.end();

  req.on('upgrade', function(res, socket, upgradeHead) {
    socket.setEncoding('utf8');
    socket.on('data', function(chunk) {
      console.log('Client: got %s', chunk);
    });

    socket.write('PACKPA');
    socket.write('CK');
    socket.end();

    srv.close();
  });
});

process.on('exit', function() {
  assert.equal(serverRecvCount, 2);
  assert(serverRecvEnd);
});
