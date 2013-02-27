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
var nconns = 0;
// test only in TLSv1 to use DES which is no longer supported TLSv1.2
// to be safe when the default method is updated in the future
var SSL_Method = 'TLSv1_method';
var localhost = '127.0.0.1';

process.on('exit', function() {
  assert.equal(nconns, 4);
});

function test(honorCipherOrder, clientCipher, expectedCipher, cb) {
  var soptions = {
    secureProtocol: SSL_Method,
    keystore: common.fixturesDir + '/keys/agent2.jks',
    passphrase: 'secure',
    //ciphers: 'AES256-SHA:RC4-SHA:DES-CBC-SHA',
    ciphers: 'TLS_RSA_WITH_AES_256_CBC_SHA:SSL_RSA_WITH_RC4_128_SHA:SSL_RSA_WITH_DES_CBC_SHA',
    honorCipherOrder: !!honorCipherOrder
  };

  var server = tls.createServer(soptions, function(cleartextStream) {
    nconns++;
  });
  server.listen(common.PORT, localhost, function() {
    var coptions = {
      rejectUnauthorized: false,
      secureProtocol: SSL_Method
    };
    if (clientCipher) {
      coptions.ciphers = clientCipher;
    }
    var client = tls.connect(common.PORT, localhost, coptions, function() {
      var cipher = client.getCipher();
      client.end();
      server.close();
      console.log('test honorOrder = ' + honorCipherOrder + 
                  ' client order ' + clientCipher +
                  ' expected ' + expectedCipher +
                  ' got ' + cipher.name);
      assert.equal(cipher.name, expectedCipher);
      if (cb) cb();
    });
  });
}

test1(); 
function test1() {
  // Client has the preference of cipher suites by default
  test(false, 
       'SSL_RSA_WITH_DES_CBC_SHA:SSL_RSA_WITH_RC4_128_SHA:TLS_RSA_WITH_AES_256_CBC_SHA',
      'SSL_RSA_WITH_DES_CBC_SHA', test2);
}

function test2() {
  // Server has the preference of cipher suites where AES256-SHA is in
  // the first.
  test(true, 
       'SSL_RSA_WITH_DES_CBC_SHA:SSL_RSA_WITH_RC4_128_SHA:TLS_RSA_WITH_AES_256_CBC_SHA', 
  'TLS_RSA_WITH_AES_256_CBC_SHA', test3);

   
}

function test3() {
  // Server has the preference of cipher suites. RC4-SHA is given
  // higher priority over DES-CBC-SHA among client cipher suites.
  test(true, 
       'SSL_RSA_WITH_DES_CBC_SHA:SSL_RSA_WITH_RC4_128_SHA', 
       'SSL_RSA_WITH_RC4_128_SHA', test4);
}

function test4() {
  // As client has only one cipher, server has no choice in regardless
  // of honorCipherOrder.
  test(true, 'SSL_RSA_WITH_DES_CBC_SHA', 
       'SSL_RSA_WITH_DES_CBC_SHA');
}
