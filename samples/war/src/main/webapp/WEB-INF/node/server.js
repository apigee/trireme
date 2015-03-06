var fs = require('fs');
var http = require('http');
var path = require('path');
var util = require('util');

var PORT = process.env.PORT || 33333;

var counter = 0;

function handleRequest(req, resp) {
  console.log('%s %s', req.method, req.url);

  if (/[^/]*\/servlet\/update$/.test(req.url)) {
    updateCounter(resp);
  } else if (/[^/]*\/servlet\/static$/.test(req.url)) {
    serveStatic(resp);

  } else {
    resp.writeHead(404);
    resp.end();
  }
}

function updateCounter(resp) {
  counter++;

  var body = util.format(
    '<html><body>' +
    '<h1>Dynamic Node.js Code</h1>' +
    '<p>Counter is now %d</p>' +
    '<p><a href="..">Back to top</a></p></body></html>', counter);

  resp.writeHead(200, {
    'content-type': 'text/html'
  });
  resp.end(body);
}

function serveStatic(resp) {
  fs.readFile(
    path.join(__dirname, 'example.html'),
    function(err, data) {
      if (err) {
        resp.writeHead(500, {
          'content-type': 'text/plain'
        });
        resp.end(err.toString());

      } else {
        resp.writeHead(200, {
          'content-type': 'text/html'
        });
        resp.end(data);
      }
    });
}

var server = http.createServer(handleRequest);
server.listen(PORT, function() {
  console.log('Listening on %d', PORT);
});
