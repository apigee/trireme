var express = require('express');
var app = express();

app.get('/dogs', function(req, res) {
  res.setHeader('Content-Type', 'text/plain');
  res.end('I like dogs');
});

app.listen(33333);
console.log('Listening on port 33333');
