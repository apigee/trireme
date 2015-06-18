var http = require('http');
var util = require('util');

var PORT = process.env.PORT || 8080;

function handleRequest(req, resp) {
  console.log('%s %s', req.method, req.url);

  if (req.method === 'POST') {
    if (req.url == '/test') {
      writeEchoResponse(req, resp);
    } else if (req.url == '/test/count') {
      countData(req, resp);
    } else {
      writeError(resp, 404);
    }
  } else if (req.method == 'GET') {
    if (req.url === '/test/delay') {
      setTimeout(function() {
        writeResponse(resp);
      }, 1000);
    } else if (req.url === '/test/throw') {
      throw new Error('Oops!');
    } else if (req.url === '/test/exit') {
      process.exit(22);
    } else if (req.url === '/test/swallow') {
      // Do nothing!
    } else {
      writeResponse(resp);
    }
  } else {
    writeError(resp, 405);
  }
}

function writeResponse(resp) {
  resp.writeHead(200, {
    'CustomHeader': 'foo'
  });
  resp.end('Hello, World!');
}

function writeError(resp, code) {
  resp.writeHead(code);
  resp.end();
}

function writeEchoResponse(req, resp) {
  var msg = '';
  req.setEncoding('utf8');
  req.on('data', function(chunk) {
    msg += chunk;
  });
  req.on('end', function() {
    resp.end(msg);
  });
}

function countData(req, resp) {
  var length = 0;
  var chunks = 0;

  req.on('data', function(chunk) {
    length += chunk.length;
    chunks++;
  });
  req.on('end', function() {
    console.log('Received %d bytes in %d chunks', length, chunks);
    resp.end(util.format('%d', length));
  });
}

var server = http.createServer(handleRequest);
server.listen(PORT, function() {
  console.log('Listening on %d', PORT);
});
