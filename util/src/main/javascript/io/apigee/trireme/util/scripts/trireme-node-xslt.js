/*
 * Copyright 2014 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

var assert = require('assert');
var fs = require('fs');
var binding = process.binding('xml-wrap');

function setTransformer(transformerClass) {
  bindings.setTransformer(transformerClass);
}

function readXsltString(source) {
  var ss = binding.createStylesheet(source);
  return {
    stylesheet: ss
  };
}

function readXsltFile(fileName) {
  var source = fs.readFileSync(fileName);
  var ss = binding.createStylesheet(source);
  return {
    stylesheet: ss
  };
}

function readXmlString(source) {
  var doc = binding.createDocument(source);
  return {
    document: doc
  };
}

function readXmlFile(fileName) {
  var source = fs.readFileSync(fileName);
  var doc = binding.createDocument(source);
  return {
    document: doc
  };
}

function transform(stylesheet, document, p) {
  assert(stylesheet.stylesheet);
  assert(document.document);

  var parray = (p ? p : []);
  if ((parray.length % 2) != 0) {
    throw new Error('parameters must be an array of an even length');
  }

  var params = {};
  for (var i = 0; i < parray.length; i += 2) {
    params[parray[i]] = parray[i + 1];
  }
  return binding.transform(stylesheet.stylesheet, document.document, params);
}

function notImplemented() {
  throw new Error('Not implemented');
}

module.exports.setTransformer = setTransformer;
module.exports.readXsltString = readXsltString;
module.exports.readXsltFile = readXsltFile;
module.exports.readXmlString = readXmlString;
module.exports.readXmlFile = readXmlFile;
module.exports.readHtmlString = notImplemented;
module.exports.readHtmlFile = notImplemented;
module.exports.transform = transform;