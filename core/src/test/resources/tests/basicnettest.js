var net = require('net');
var assert = require('assert');

var PORT = 22222;
var MSG = 'Hello, Server!';
var MSG2 = 'Hi, Client!';

var svr = net.createServer(onConnection);
svr.listen(PORT, onListening);

var fromClient = '';

function onConnection(conn) {
  conn.on('data', function(chunk) {
    fromClient += chunk;
    if (fromClient === MSG) {
      // Got the whole message
      conn.write(MSG2);
    }
  });
}

var fromServer = '';

function onListening() {
  var conn = net.connect(PORT, function() {
    conn.end(MSG);
  });
  conn.on('data', function(chunk) {
    fromServer += chunk;
  });
  conn.on('end', function() {
    svr.close();
  });
}

process.on('exit', function() {
  assert.equal(fromClient, MSG);
  assert.equal(fromServer, MSG2);
});
