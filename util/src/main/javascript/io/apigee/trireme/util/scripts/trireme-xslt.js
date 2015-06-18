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

var binding = process.binding('xml-wrap');

function setTransformer(transformerClass) {
  binding.setTransformer(transformerClass);
}

function compileStylesheet(stylesheet) {
  checkInput(stylesheet, 'stylesheet');
  return binding.createStylesheet(stylesheet);
}

function transform(stylesheet, document, p, callback) {
  if (!stylesheet) {
    throw new Error('stylesheet must be set');
  }
  if (typeof p === 'function') {
    callback = p;
    p = undefined;
  }
  if (p && (typeof p !== 'object')) {
    throw new Error('parameters must be an object');
  }
  if (callback && (typeof callback !== 'function')) {
    throw new Error('callback must be a function');
  }
  checkInput(document, 'document');
  var parameters = (p ? p : {});

  try {
    var parsedDoc = binding.createDocument(document);

    var cb;
    if (callback) {
      cb = function(err, result) {
        callback(err, result);
      };
    }

    var result = binding.transform(stylesheet, parsedDoc, parameters, cb);
    if (!cb) {
      return result;
    }

  } catch (e) {
    if (callback) {
      callback(e);
    } else {
      throw e;
    }
  }
}

function checkInput(inp, name) {
  if ((typeof inp !== 'string') && (!(inp instanceof Buffer))) {
    throw new Error(name + ' must be a string or a Buffer');
  }
}

module.exports.setTransformer = setTransformer;
module.exports.compileStylesheet = compileStylesheet;
module.exports.transform = transform;
