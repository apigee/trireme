var assert = require('assert');
var http = require('http');
var urlparse = require('url');
var util = require('util');

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
    req.on('end', function() {
      resp.end('ok');
    });
  } else if ((req.url === '/throwOnData') || (req.url === '/throwOnEnd')) {
    req.on('end', function() {
      resp.end('Should not get here!');
    });
  } else {
    throw new Error('Unknown URL ' + req.url);
  }
});

var baseUrl;

svr.listen(0, function() {
  baseUrl = util.format('http://localhost:%d/', svr.address().port);
  doTest('GET', 'ok', false, 200, function() {
    doTest('GET', 'throw', false, 500, function() {
      doTest('POST', 'throwOnData', false, 500, function() {
        doTest('POST', 'throwOnEnd', false, 500, function() {
          doTest('POST', 'headThenThrow', false, 500, function() {
            // TODO the client side of HTTP doesn't seem to handle this right
            //doTest('GET', 'writeThenThrow', false, 500, function() {
              doTest('GET', 'ok', true, 200);
            });
          //});
        });
      });
    });
  });
});

function doTest(verb, url, shouldClose, code, next) {
  opts = urlparse.parse(baseUrl + url);
  opts.method = verb;
  req = http.request(opts, function(resp) {
    var respData = '';
    resp.on('readable', function() {
      var data;
      do {
        data = resp.read();
        if (data !== null) {
          respData += data;
        }
      } while (data !== null);
    });
    resp.on('end', function() {
      console.log('Response data: "%s"', respData);
      if (shouldClose) {
        svr.close();
      } else {
        next();
      }
    });

    console.log('Got %d for %s', resp.statusCode, url);
    assert.equal(code, resp.statusCode);
  });

  if (verb === 'GET') {
    req.end();
  } else {
    req.end('Hello, there test guy!');
  }
}
