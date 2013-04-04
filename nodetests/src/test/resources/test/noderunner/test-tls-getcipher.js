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
var fs = require('fs');
//var cipher_list = ['RC4-SHA', 'AES256-SHA'];
var cipher_list = ['SSL_RSA_WITH_RC4_128_SHA', 'TLS_RSA_WITH_AES_128_CBC_SHA'];
var cipher_version_pattern = /TLS|SSL/;
var options = {
  keystore: common.fixturesDir + '/keys/agent2.jks',
  passphrase: 'secure',
  ciphers: cipher_list.join(':'),
  honorCipherOrder: true
};

var nconns = 0;

process.on('exit', function() {
  assert.equal(nconns, 1);
});

var server = tls.createServer(options, function(cleartextStream) {
  console.log('Got a connection');
  nconns++;
});

server.listen(common.PORT, '127.0.0.1', function() {
  console.log('Listening');
  var client = tls.connect({
    host: '127.0.0.1',
    port: common.PORT,
    rejectUnauthorized: false
  }, function() {
    var cipher = client.getCipher();
    console.log('Got a connection with cipher ' + cipher.name);
    assert((cipher.name === cipher_list[0]) || 
           (cipher.name === cipher_list[1]));
    assert(cipher_version_pattern.test(cipher.version));
    client.end();
    server.close();
  });
});
