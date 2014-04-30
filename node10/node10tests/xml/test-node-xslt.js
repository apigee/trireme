var assert = require('assert');
var fs = require('fs');
var path = require('path');

var xslt = require('node_xslt');

function testTemplate(templFile, docFile, resultFile, p) {
  var params = (p ? p : []);
  var ss = xslt.readXsltFile(path.join(__dirname, templFile));
  var doc = xslt.readXmlFile(path.join(__dirname, docFile));

  var result = xslt.transform(ss, doc, params);

  var desired = fs.readFileSync(path.join(__dirname, resultFile), { encoding: 'utf8' });

  // Desired results were produced by xsltproc, which produces slightly different whitespace than Java
  var d = removeWhitespace(desired);
  var r = removeWhitespace(result);

  assert.equal(d, r);
}

function testTemplateString(templFile, docFile, resultFile) {
  var templString = fs.readFileSync(path.join(__dirname, templFile), { encoding: 'utf8' });
  var docString = fs.readFileSync(path.join(__dirname, docFile), { encoding: 'utf8' });

  var ss = xslt.readXsltString(templString);
  var doc = xslt.readXmlString(docString);

  var result = xslt.transform(ss, doc, []);

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
             './fixtures/for-each-param-result-1987.xml', [ 'year', '1987' ]);
testTemplate('./fixtures/for-each-param.xsl', './fixtures/catalog.xml',
             './fixtures/for-each-param-result-1991.xml', [ 'year', '1991' ]);

// Should throw on an invalid stylesheet
assert.throws(function() {
  xslt.readXsltString('<Bogus>This is a bogus stylesheet</Bogus>');
});

// Should throw on bogus parameters
assert.throws(function() {
  xslt.transform({ foo: 'bar' }, 'this is not what you want', []);
});

var ss = xslt.readXsltFile(path.join(__dirname, './fixtures/apply-templates.xsl'));
var doc = xslt.readXmlString('This is not even XML');

// Valid stylesheet, doc does not contain XML
assert.throws(function() {
  xslt.transform(ss, doc, []);
});

