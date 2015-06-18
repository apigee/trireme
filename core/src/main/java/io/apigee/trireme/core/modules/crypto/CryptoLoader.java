/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.core.modules.crypto;

import io.apigee.trireme.kernel.crypto.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Provider;
import java.util.ServiceLoader;

/**
 * This is a singleton class to ensure that we only load the BouncyCastle crypto provider once
 * per JVM, but for startup time reasons we don't load it until we actually require the "crypto"
 * module once.
 */

public class CryptoLoader
{
    private static final Logger log = LoggerFactory.getLogger(CryptoLoader.class.getName());

    private CryptoService cryptoService;
    private Provider cryptoProvider;

    private static final CryptoLoader myself = new CryptoLoader();

    private CryptoLoader()
    {
        ServiceLoader<CryptoService> loc = ServiceLoader.load(CryptoService.class);
        if (loc.iterator().hasNext()) {
            if (log.isDebugEnabled()) {
                log.debug("Using crypto service implementation {}", cryptoService);
            }
            cryptoService = loc.iterator().next();
            cryptoProvider = cryptoService.getProvider();
        } else if (log.isDebugEnabled()) {
            log.debug("No crypto service available");
        }
    }

    public static CryptoLoader get() {
        return myself;
    }

    public CryptoService getCryptoService() {
        return cryptoService;
    }

    public Provider getCryptoProvider() {
        return cryptoProvider;
    }
}
