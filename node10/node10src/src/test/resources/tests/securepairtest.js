var assert = require('assert');
var crypto = require('crypto');
var fs = require('fs');
var path = require('path');
var tls = require('tls');

var keyStorePath = path.join(__dirname, './agent1.jks');

var serverContext = crypto.createCredentials({
  keystore: keyStorePath, passphrase: 'secure' });
var clientContext = crypto.createCredentials();

var serverPair = tls.createSecurePair(serverContext, true);
var clientPair = tls.createSecurePair(clientContext, false);

// Connect the output of the server pair to the input of the client pair, with debugging
serverPair.encrypted.on('data', function(chunk) {
  console.log('Server %d -> client', chunk.length);
  clientPair.encrypted.write(chunk);
});
serverPair.encrypted.on('end', function() {
  console.log('Server end -> client');
  clientPair.encrypted.end();
});

clientPair.encrypted.on('data', function(chunk) {
  console.log('Client %d -> server', chunk.length);
  serverPair.encrypted.write(chunk);
});
clientPair.encrypted.on('end', function() {
  console.log('Client end -> server');
  serverPair.encrypted.end();
});

// Consume output from both sides
serverPair.cleartext.setEncoding('utf8');
var data = '';
var serverGotHello = false;
var serverGotGoodbye = false;
var serverGotEnd = false;
var serverSecure = false;

serverPair.cleartext.on('data', function(chunk) {
  console.log('Server got "%s"', chunk);
  data += chunk;
  if (/Goodbye/.test(data)) {
    serverGotGoodbye = true;
    serverPair.cleartext.end('END');
  } else {
    serverGotHello = true;
    serverPair.cleartext.write('ACK');
  }
});
serverPair.cleartext.on('end', function() {
  console.log('Server got end');
  serverGotEnd = true;
});
serverPair.cleartext.on('close', function() {
  console.log('Server got close');
});
serverPair.on('secure', function() {
  console.log('Server is secure. Authorized = %s', serverPair.cleartext.authorized);
  serverSecure = true;
});
serverPair.on('error', function(err) {
  console.log('Server got error %j', err);
});

clientPair.cleartext.setEncoding('utf8');
var clientData = '';
var clientGotAck = false;
var clientGotEnd = false;
var clientSecure = false;

clientPair.cleartext.on('data', function(chunk) {
  console.log('Client got "%s"', chunk);
  clientData += chunk;
  if (/ACKACKACK/.test(clientData)) {
    console.log('Client sending end');
    clientPair.cleartext.end('Goodbye');
  } else {
    clientGotAck = true;
    clientPair.cleartext.write('Hello');
  }
});
clientPair.cleartext.on('end', function() {
  console.log('Client got end');
  clientGotEnd = true;
  clearTimeout(doneTimer);
});
clientPair.cleartext.on('close', function() {
  console.log('Client got close');
});
clientPair.on('secure', function() {
  console.log('Client is secure. Authorized = %s', clientPair.cleartext.authorized);
  clientSecure = true;
});
clientPair.on('error', function(err) {
  console.log('Client got error %j', err);
});

// The timeout will ensure that we don't exit until the test is complete
var doneTimer = setTimeout(function() {
}, 5000);

console.log('Writing first thing');
clientPair.cleartext.write('Hello');

process.on('exit', function() {
  assert(serverGotHello);
  assert(serverGotGoodbye);
  assert(clientGotAck);
  assert(clientGotEnd);
  assert(serverGotEnd);
  assert(clientSecure);
  assert(serverSecure);
});
