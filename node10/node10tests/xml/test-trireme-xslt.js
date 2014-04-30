var assert = require('assert');
var fs = require('fs');
var path = require('path');

var xslt = require('trireme-xslt');

var asyncRequests = 0;
var asyncSuccesses = 0;

function testTemplate(templFile, docFile, resultFile, params) {
  var ssText = fs.readFileSync(path.join(__dirname, templFile));
  var ss = xslt.compileStylesheet(ssText);

  var docText = fs.readFileSync(path.join(__dirname, docFile));

  var result = xslt.transform(ss, docText, params);

  var desired = fs.readFileSync(path.join(__dirname, resultFile), { encoding: 'utf8' });

  // Desired results were produced by xsltproc, which produces slightly different whitespace than Java
  var d = removeWhitespace(desired);
  var r = removeWhitespace(result);

  assert.equal(d, r);
}

function testTemplateAsync(templFile, docFile, resultFile, p) {
  var ssText = fs.readFileSync(path.join(__dirname, templFile));
  var ss = xslt.compileStylesheet(ssText);

  var docText = fs.readFileSync(path.join(__dirname, docFile));

  var params = (p ? p : {});
  asyncRequests++;
  var result = xslt.transform(ss, docText, params, function(err, result) {
    if (err) {
      console.error('Error in async transform: %j', err);
    } else {
       var desired = fs.readFileSync(path.join(__dirname, resultFile), { encoding: 'utf8' });
       var d = removeWhitespace(desired);
       var r = removeWhitespace(result);
      if (d === r) {
        asyncSuccesses++;
      } else {
        console.error('Async transform result does not match desired result');
      }
    }
  });
}

function testTemplateString(templFile, docFile, resultFile) {
  var templString = fs.readFileSync(path.join(__dirname, templFile), { encoding: 'utf8' });
  var docString = fs.readFileSync(path.join(__dirname, docFile), { encoding: 'utf8' });

  var ss = xslt.compileStylesheet(templString);

  var result = xslt.transform(ss, docString);

  var desired = fs.readFileSync(path.join(__dirname, resultFile), { encoding: 'utf8' });

  // Desired results were produced by xsltproc, which produces slightly different whitespace than Java
  var d = removeWhitespace(desired);
  var r = removeWhitespace(result);

  assert.equal(d, r);
}

function removeWhitespace(s) {
  // Remove all whitespace between HTML tags
  return s.replace(/>\s+</g, '><');
}

testTemplate('./fixtures/apply-templates.xsl', './fixtures/catalog.xml', './fixtures/apply-templates-result.xml');
testTemplate('./fixtures/for-each.xsl', './fixtures/catalog.xml', './fixtures/for-each-result.xml');
testTemplate('./fixtures/sort.xsl', './fixtures/catalog.xml', './fixtures/sort-result.xml');
testTemplate('./fixtures/value-of.xsl', './fixtures/catalog.xml', './fixtures/value-of-result.xml');
testTemplateString('./fixtures/apply-templates.xsl', './fixtures/catalog.xml', './fixtures/apply-templates-result.xml');

testTemplate('./fixtures/for-each-param.xsl', './fixtures/catalog.xml',
             './fixtures/for-each-param-result-1987.xml', { year: '1987' });
testTemplate('./fixtures/for-each-param.xsl', './fixtures/catalog.xml',
             './fixtures/for-each-param-result-1991.xml', { year: '1991' });

testTemplateAsync('./fixtures/apply-templates.xsl', './fixtures/catalog.xml', './fixtures/apply-templates-result.xml');
testTemplateAsync('./fixtures/for-each.xsl', './fixtures/catalog.xml', './fixtures/for-each-result.xml');
testTemplateAsync('./fixtures/sort.xsl', './fixtures/catalog.xml', './fixtures/sort-result.xml');
testTemplateAsync('./fixtures/value-of.xsl', './fixtures/catalog.xml', './fixtures/value-of-result.xml');

testTemplateAsync('./fixtures/for-each-param.xsl', './fixtures/catalog.xml',
             './fixtures/for-each-param-result-1987.xml', { year: '1987' });
testTemplateAsync('./fixtures/for-each-param.xsl', './fixtures/catalog.xml',
             './fixtures/for-each-param-result-1991.xml', { year: '1991' });

process.on('exit', function() {
  assert.equal(asyncRequests, asyncSuccesses);
});

// Should throw on an invalid stylesheet
assert.throws(function() {
  xslt.compileStylesheet('<Bogus>This is a bogus stylesheet</Bogus>');
});

// Should throw on bogus parameters
assert.throws(function() {
  xslt.transform({ foo: 'bar' }, 'this is not what you want', []);
});

var ss = xslt.compileStylesheet(fs.readFileSync(path.join(__dirname, './fixtures/apply-templates.xsl')));

// Valid stylesheet, doc does not contain XML
assert.throws(function() {
  xslt.transform(ss, 'This is not even XML');
});

