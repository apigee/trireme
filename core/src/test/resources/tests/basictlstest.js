var tls = require('tls');
var assert = require('assert');
var fs = require('fs');
var path = require('path');

var PORT = 22223;
var MSG = 'Hello, Server!';
var MSG2 = 'Hi, Client!';

var svr = tls.createServer({
  keystore: path.join(__dirname, 'agent1.jks'),
  passphrase: 'secure'
}, onConnection);
svr.listen(PORT, onListening);

var fromClient = '';

function onConnection(conn) {
  console.log('Server got connection');
  conn.on('data', function(chunk) {
    console.log('Server got chunk of length %d', chunk.length);
    fromClient += chunk;
    if (fromClient === MSG) {
      // Got the whole message
      console.log('Server writing back to the client');
      conn.write(MSG2);
    }
  });
  conn.on('end', function() {
    console.log('Server got end from client');
    conn.end();
  });
}

var fromServer = '';

function onListening() {
  console.log('Client connecting');
  var conn = tls.connect({
      port: PORT,
      rejectUnauthorized: false
  }, function() {
    console.log('Client connected');
    conn.write(MSG);
  });
  conn.on('data', function(chunk) {
    console.log('Client got chunk of length %d', chunk.length);
    fromServer += chunk;
    if (fromServer === MSG2) {
      console.log('Client ending socket');
      conn.end();
    }
  });
  conn.on('end', function() {
    console.log('Client got end');
    svr.close();
  });
}

process.on('exit', function() {
  assert.equal(fromClient, MSG);
  assert.equal(fromServer, MSG2);
});
