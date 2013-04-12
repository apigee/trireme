var http = require('http');
var assert = require('assert');

var svr = http.createServer(function(req, resp) {
  console.log('Got %s', req.url);

  req.on('readable', function() {
    if (req.url === '/throwOnData') {
      throw new Error('Error in readable');
    }
    req.read();
  });

  req.on('end', function() {
    if (req.url === '/throwOnEnd') {
      throw new Error('Error in end');
    }
  });

  if (req.url === '/throw') {
    throw new Error('Error from server');
  } else if (req.url === '/headThenThrow') {
    resp.writeHead(201, {'X-Apigee-Throwing' : 'true' });
    throw new Error('Throwing after writeHead');
  } else if (req.url === '/writeThenThrow') {
    resp.writeHead(200, {'Content-Type' : 'text/plain' });
    resp.write('Hello, World!');
    throw new Error('Throwing after write');
  } else if (req.url === '/ok') {
    resp.end('ok');
  } else {
    throw new Error('Unkown URL ' + req.url);
  }
});

svr.listen(33333, function() {
  doTest('ok', false, 200, function() {
    doTest('throw', false, 500, function() {
      doTest('throwOnData', false, 500, function() {
        doTest('throwOnEnd', false, 500, function() {
          doTest('headThenThrow', false, 500, function() {
            doTest('ok', true, 200);
          });
        });
      });
    });
  });
});

function doTest(url, shouldClose, code, next) {
  http.get('http://localhost:33333/' + url, function(resp) {
    resp.on('readable', function() {
      resp.read();
    });
    resp.on('end', function() {
      if (shouldClose) {
        svr.close();
      } else {
        next();
      }
    });

    console.log('Got %d for %s', resp.statusCode, url);
    assert.equal(code, resp.statusCode);
  });
}
