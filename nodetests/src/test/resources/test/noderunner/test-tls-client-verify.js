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

if (!process.versions.openssl) {
  console.error('Skipping because node compiled without OpenSSL.');
  process.exit(0);
}


var hosterr = 'Hostname/IP doesn\'t match certificate\'s altnames';
var testCases =
    [{ truststore: 'ca1-cert',
       keystore: 'agent2',
       servers: [
         { ok: true, keystore: 'agent1' },
         { ok: false, keystore: 'agent2' },
         { ok: false, keystore: 'agent3' }
       ]
     },

     { truststore: null,
       keystore: 'agent2',
       servers: [
         { ok: false, keystore: 'agent1' },
         { ok: false, keystore: 'agent2' },
         { ok: false, keystore: 'agent3' }
       ]
     },

     { truststore: 'ca1-and-2-cert',
       keystore: 'agent2',
       servers: [
         { ok: true, keystore: 'agent1' },
         { ok: false, keystore: 'agent2' },
         { ok: true, keystore: 'agent3' }
       ]
     }
    ];


var common = require('../common');
var assert = require('assert');
var fs = require('fs');
var tls = require('tls');


function filenameJKS(n) {
  if (n === null) {
    return null;
  }
  return require('path').join(common.fixturesDir, 'keys', n + '.jks');
}

var successfulTests = 0;

function testServers(index, servers, clientOptions, cb) {
  var serverOptions = servers[index];
  if (!serverOptions) {
    cb();
    return;
  }

  var ok = serverOptions.ok;

  if (serverOptions.keystore) {
    serverOptions.keystore = filenameJKS(serverOptions.keystore);
    serverOptions.passphrase = 'secure';
  }

  var server = tls.createServer(serverOptions, function(s) {
    s.end('hello world\n');
  });

  console.log('Starting server using keystore ' + 
              serverOptions.keystore);

  server.listen(common.PORT, function() {
    var b = '';

    console.error('connecting using keystore ' +
                  clientOptions.keystore +
                  ' and trust store ' +
                  clientOptions.truststore);
    var client = tls.connect(clientOptions, function() {
      console.log('Successful connect');
      assert(ok);
      server.close();
    });

    client.on('error', function(err) {
      console.error('Received TLS error ' + err);
      assert(!ok);
      server.close();
    });

    client.on('data', function(d) {
      b += d.toString();
    });

    client.on('end', function() {
      assert.equal('hello world\n', b);
    });

    client.on('close', function() {
      testServers(index + 1, servers, clientOptions, cb);
    });
  });
}


function runTest(testIndex) {
  var tcase = testCases[testIndex];
  if (!tcase) return;

  var clientOptions = {
    port: common.PORT,
    truststore: filenameJKS(tcase.truststore),
    keystore: filenameJKS(tcase.keystore),
    passphrase: 'secure',
  };


  testServers(0, tcase.servers, clientOptions, function() {
    successfulTests++;
    runTest(testIndex + 1);
  });
}


runTest(0);


process.on('exit', function() {
  console.log('successful tests: %d', successfulTests);
  assert.equal(successfulTests, testCases.length);
});
