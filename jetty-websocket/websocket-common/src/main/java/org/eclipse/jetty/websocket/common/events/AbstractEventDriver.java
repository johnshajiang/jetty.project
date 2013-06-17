//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.events;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.message.MessageAppender;

/**
 * EventDriver is the main interface between the User's WebSocket POJO and the internal jetty implementation of WebSocket.
 */
public abstract class AbstractEventDriver implements IncomingFrames, EventDriver
{
    private static final Logger LOG = Log.getLogger(AbstractEventDriver.class);
    protected final WebSocketPolicy policy;
    protected final Object websocket;
    protected WebSocketSession session;
    protected MessageAppender activeMessage;

    public AbstractEventDriver(WebSocketPolicy policy, Object websocket)
    {
        this.policy = policy;
        this.websocket = websocket;
    }

    protected void appendMessage(ByteBuffer buffer, boolean fin) throws IOException
    {
        activeMessage.appendMessage(buffer,fin);

        if (fin)
        {
            activeMessage.messageComplete();
            activeMessage = null;
        }
    }

    protected void dispatch(Runnable runnable)
    {
        session.dispatch(runnable);
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public WebSocketSession getSession()
    {
        return session;
    }

    @Override
    public final void incomingError(Throwable e)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("incoming(WebSocketException)",e);
        }

        if (e instanceof CloseException)
        {
            CloseException close = (CloseException)e;
            terminateConnection(close.getStatusCode(),close.getMessage());
        }

        onError(e);
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{}.onFrame({})",websocket.getClass().getSimpleName(),frame);
        }

        try
        {
            onFrame(frame);

            byte opcode = frame.getType().getOpCode();
            switch (opcode)
            {
                case OpCode.CLOSE:
                {
                    boolean validate = true;
                    CloseInfo close = new CloseInfo(frame,validate);

                    // notify user websocket pojo
                    onClose(close);

                    // process handshake
                    session.getConnection().getIOState().onCloseRemote(close);

                    return;
                }
                case OpCode.PING:
                {
                    ByteBuffer pongBuf;
                    if (frame.hasPayload())
                    {
                        pongBuf = ByteBuffer.allocate(frame.getPayload().remaining());
                        BufferUtil.put(frame.getPayload(),pongBuf);
                        BufferUtil.flipToFlush(pongBuf,0);
                    }
                    else
                    {
                        pongBuf = ByteBuffer.allocate(0);
                    }
                    onPong(pongBuf);
                    session.getRemote().sendPong(pongBuf);
                    break;
                }
                case OpCode.BINARY:
                {
                    onBinaryFrame(frame.getPayload(),frame.isFin());
                    return;
                }
                case OpCode.TEXT:
                {
                    onTextFrame(frame.getPayload(),frame.isFin());
                    return;
                }
                case OpCode.CONTINUATION:
                {
                    onContinuationFrame(frame.getPayload(),frame.isFin());
                    return;
                }
                default:
                {
                    LOG.debug("Unhandled OpCode: {}",opcode);
                }
            }
        }
        catch (NotUtf8Exception e)
        {
            terminateConnection(StatusCode.BAD_PAYLOAD,e.getMessage());
        }
        catch (CloseException e)
        {
            terminateConnection(e.getStatusCode(),e.getMessage());
        }
        catch (Throwable t)
        {
            unhandled(t);
        }
    }

    @Override
    public void onContinuationFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (activeMessage == null)
        {
            throw new IOException("Out of order Continuation frame encountered");
        }

        appendMessage(buffer,fin);
    }

    @Override
    public void onPong(ByteBuffer buffer)
    {
        /* TODO: provide annotation in future */
    }

    @Override
    public void openSession(WebSocketSession session)
    {
        LOG.debug("openSession({})",session);
        this.session = session;
        try
        {
            this.onConnect();
        }
        catch (Throwable t)
        {
            unhandled(t);
        }
    }

    protected void terminateConnection(int statusCode, String rawreason)
    {
        String reason = rawreason;
        reason = StringUtil.truncate(reason,(WebSocketFrame.MAX_CONTROL_PAYLOAD - 2));
        LOG.debug("terminateConnection({},{})",statusCode,rawreason);
        session.close(statusCode,reason);
    }

    private void unhandled(Throwable t)
    {
        LOG.warn("Unhandled Error (closing connection)",t);
        onError(t);

        // Unhandled Error, close the connection.
        switch (policy.getBehavior())
        {
            case SERVER:
                terminateConnection(StatusCode.SERVER_ERROR,t.getClass().getSimpleName());
                break;
            case CLIENT:
                terminateConnection(StatusCode.POLICY_VIOLATION,t.getClass().getSimpleName());
                break;
        }
    }
}
