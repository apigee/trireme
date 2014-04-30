var fs = require('fs');
var path = require('path');

var xslt = require('trireme-xslt');

var ITERATIONS = 100;
var ASYNC = true;

var ssText = fs.readFileSync(path.join(__dirname, './fixtures/for-each-param.xsl'));
var ss = xslt.compileStylesheet(ssText);

// Read the doc as text to prevent biasing results due to string-to-buffer comparisons
var docText = fs.readFileSync(path.join(__dirname, './fixtures/catalog.xml'), { encoding: 'utf8' });
var params = { year: '1987' };

var start = process.hrtime();
var completed  = 0;

for (var i = 0; i < ITERATIONS; i++) {
  if (ASYNC) {
    xslt.transform(ss, docText, params, function(err, result) {
      if (err) {
        console.error('Error in transformation: %j', err);
      }
      completed++;
      if (completed === ITERATIONS) {
        endTest();
      }
    });

  } else {
    xslt.transform(ss, docText, params);
    completed++;
    if (completed === ITERATIONS) {
      endTest();
    }
  }
}

function endTest() {
  var elapsed = process.hrtime(start);
  console.log('Async = %s', ASYNC);
  console.log('Completed %d iterations in %j', completed, elapsed);
}