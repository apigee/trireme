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
package io.apigee.trireme.servlet;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.Sandbox;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.ScriptStatusListener;
import io.apigee.trireme.kernel.net.NetworkPolicy;
import io.apigee.trireme.net.spi.HttpServerStub;
import io.apigee.trireme.servlet.internal.EnvironmentManager;
import io.apigee.trireme.servlet.internal.ResponseChunk;
import io.apigee.trireme.servlet.internal.ResponseError;
import io.apigee.trireme.servlet.internal.ScriptState;
import io.apigee.trireme.servlet.internal.ServletChunk;
import io.apigee.trireme.servlet.internal.ServletRequest;
import io.apigee.trireme.servlet.internal.ServletResponse;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TriremeServlet
    extends HttpServlet
{
    public static final String SCRIPT_BASE = "TriremeScript";
    public static final String SCRIPT_SANDBOX = "TriremeSandbox";
    public static final String SCRIPT_STARTUP_TIMEOUT = "TriremeStartupTimeout";
    public static final String SCRIPT_RESPONSE_TIMEOUT = "TriremeResponseTimeout";

    public static final long DEFAULT_STARTUP_TIMEOUT = 10L;

    private static final int BUFFER_SIZE = 8192;

    private final ScriptState state = new ScriptState();

    private volatile ScriptStatus scriptStatus;

    private long startupTimeout = DEFAULT_STARTUP_TIMEOUT;

    @Override
    public void init(ServletConfig config)
        throws ServletException
    {
        String scriptName = config.getInitParameter(SCRIPT_BASE);
        if (scriptName == null) {
            throw new ServletException("Cannot start servlet: " + SCRIPT_BASE + " not defined");
        }

        boolean sandboxMode = false;
        String propVal = config.getInitParameter(SCRIPT_SANDBOX);
        if (propVal != null) {
            sandboxMode = Boolean.valueOf(propVal);
        }

        propVal = config.getInitParameter(SCRIPT_STARTUP_TIMEOUT);
        if (propVal != null) {
            startupTimeout = Long.valueOf(propVal);
        }

        propVal = config.getInitParameter(SCRIPT_RESPONSE_TIMEOUT);
        if (propVal != null) {
            state.setResponseTimeout(Long.valueOf(propVal));
        }

        String basePath = config.getServletContext().getRealPath("/");
        File scriptFile = new File(basePath, scriptName);

        if (!scriptFile.exists()) {
            throw new ServletException("Cannot start servlet: " + scriptFile + " is not found");
        }
        if (!scriptFile.canRead()) {
            throw new ServletException("Cannot start servlet: " + scriptFile + " is not readable");
        }

        NodeEnvironment env = EnvironmentManager.get().getEnvironment();

        try {
            NodeScript script;

            if (sandboxMode) {
                Sandbox sandbox = new Sandbox().
                    setFilesystemRoot(basePath).
                    setHideOSDetails(true).
                    setAllowJarLoading(false).
                    setNetworkPolicy(new NetworkPolicy()
                    {
                        @Override
                        public boolean allowConnection(InetSocketAddress addr)
                        {
                            return true;
                        }

                        @Override
                        public boolean allowListening(InetSocketAddress addrPort)
                        {
                            return false;
                        }
                    });

                script = env.createScript(new String[] { scriptName }, false);
                script.setSandbox(sandbox);

            } else {
                script = env.createScript(new String[] { scriptFile.getPath() }, false);
            }

            script.setAttachment(state);

            ScriptFuture future = script.execute();

            future.setListener(new ScriptStatusListener() {
                @Override
                public void onComplete(NodeScript script, ScriptStatus status)
                {
                    scriptStatus = status;
                }
            });

        } catch (NodeException ne) {
            throw new ServletException("Cannot start servlet: " + ne, ne);
        }

        super.init(config);
    }

    @Override
    public void destroy()
    {
        super.destroy();
    }

    @Override
    protected void service(HttpServletRequest servletReq,
                           HttpServletResponse servletResp)
        throws IOException
    {
        if (scriptStatus != null) {
            // Script exited, either during startup or later
            returnError(servletResp, 500, "Script exited with exit code " + scriptStatus.getExitCode());
            return;
        }

        try {
            // Block until the script is running
            state.get(startupTimeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            returnError(servletResp, 500, "Interrupted while waiting for server startup");
            return;
        } catch (ExecutionException e) {
            returnError(servletResp, 500, e.getCause().toString());
            return;
        } catch (TimeoutException e) {
            returnError(servletResp, 500, "Script took more than " + startupTimeout + " seconds to start");
            return;
        }

        ServletRequest req = new ServletRequest(servletReq);
        ServletResponse resp = new ServletResponse(servletResp);
        HttpServerStub stub = state.getStub();

        // Asynchronously ask Node.js to start processing the request
        stub.onRequest(req, resp);

        // Read the stream in blocking mode (which works in all servlet engines) and pass to Node.
        byte[] buf = new byte[BUFFER_SIZE];
        InputStream in = servletReq.getInputStream();
        int rc;

        do {
            rc = in.read(buf);
            if (rc > 0) {
                ByteBuffer chunk = ByteBuffer.wrap(buf, 0, rc);
                stub.onData(req, resp, new ServletChunk(chunk, false));
            }
        } while (rc >= 0);

        // Send the "end".
        stub.onData(req, resp, new ServletChunk(null, true));

        // Now block while we wait for responses to come back from Node.
        OutputStream out = null;
        Object next;
        ResponseChunk chunk;
        do {
            try {
                next = resp.getNextChunk();
                if (next instanceof ResponseError) {
                    ResponseError err = (ResponseError)next;
                    returnError(servletResp, 500,
                                err.getMsg() + '\n' + err.getStack());
                    return;
                }

                // Delay output stream creation so we can set headers and such. Some servlet engines like that.
                if (out == null) {
                    out = servletResp.getOutputStream();
                }

                chunk = (ResponseChunk)next;
                if (chunk.getBuffer() == ServletResponse.LAST_CHUNK) {
                    out.close();
                    chunk.getFuture().setSuccess();
                } else if (chunk.getBuffer() != null) {
                    ByteBuffer bb = chunk.getBuffer();
                    if (bb.hasArray()) {
                        out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining());
                        chunk.getFuture().setSuccess();
                    } else {
                        byte[] tmp = new byte[bb.remaining()];
                        bb.get(tmp);
                        out.write(tmp);
                        chunk.getFuture().setSuccess();
                    }
                }
            } catch (InterruptedException e) {
                returnError(servletResp, 500, "Interrupted while reading");
                return;
            }
        } while (chunk.getBuffer() != ServletResponse.LAST_CHUNK);
    }

    private void returnError(HttpServletResponse resp, int code, String msg)
        throws IOException
    {
        resp.setHeader("Content-Type", "text/plain");
        resp.setStatus(code);
        PrintWriter pw = resp.getWriter();
        pw.write(msg);
        pw.close();
    }
}
